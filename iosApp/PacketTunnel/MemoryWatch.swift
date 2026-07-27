import Darwin
import Foundation
import os

/// Records how close the extension is to its memory ceiling, where the record
/// survives the extension being killed.
///
/// A packet tunnel provider gets roughly 50 MB and is terminated for exceeding
/// it without being told anything: the process is simply gone, the app's own
/// stop request arrives afterwards, and the system reports the VPN as inactive.
/// From inside, that is indistinguishable from any other sudden death — which is
/// why the olcRTC-with-UDP build's two-second lifetime has stayed a guess rather
/// than a finding.
///
/// So the measurement is written to the App Group four times a second and the
/// last window of it is kept. Whatever is in the file after the process dies is
/// what was true just before it died:
///
///   * footprint climbing toward ~50 MB, headroom falling to near zero
///     → the memory ceiling, and the three-peer-connections theory is right;
///   * footprint flat and headroom wide when the process disappears
///     → it is not memory, and every minute spent on JetsamEvent reports is
///       a minute spent on the wrong question.
///
/// Either answer is worth more than the report we could not find. This is a
/// debugging facility: set `enabled` to false once it has answered.
enum MemoryWatch {

    /// Off.
    ///
    /// It answered its question — the extension was not dying of memory, and the
    /// eight-second olcRTC timeout was — and what it costs is a file rewritten
    /// four times a second inside a process with a ~50 MB ceiling. Flip to true
    /// while chasing a kill nobody can explain; leave it false in anything that
    /// ships.
    static let enabled = false

    /// 250 ms, because the window being explained is about two seconds long —
    /// a slower tick could miss the whole climb.
    private static let interval: TimeInterval = 0.25

    /// Fifty seconds of history. Enough to cover a start that dies, small
    /// enough that rewriting the file each tick stays a few kilobytes.
    private static let window = 200

    /// Every mutable field below is touched only from this queue, which is what
    /// makes `nonisolated(unsafe)` true rather than merely quiet. The extension
    /// target still builds in Swift 5 mode, where plain statics would compile;
    /// the app target is already on 6, and a prior bump is not the moment to
    /// discover that the debugging instrument is what fails to build.
    private static let queue = DispatchQueue(label: "org.proofkit.memory")
    nonisolated(unsafe) private static var timer: DispatchSourceTimer?
    nonisolated(unsafe) private static var samples: [String] = []
    nonisolated(unsafe) private static var peak: UInt64 = 0
    nonisolated(unsafe) private static var started = Date()
    nonisolated(unsafe) private static var note = "start"

    private static let log = Logger(subsystem: "org.proofkit.app", category: "memory")

    /// What the provider is doing right now, stamped onto each sample so the
    /// curve can be read against the stages in `stage.txt`.
    static func mark(_ stage: String) {
        guard enabled else { return }
        queue.async { note = stage }
    }

    static func start(container: URL) {
        guard enabled else { return }
        queue.async {
            guard timer == nil else { return }
            started = Date()
            samples.removeAll(keepingCapacity: true)
            peak = 0
            let file = container.appendingPathComponent("memory.txt")
            let source = DispatchSource.makeTimerSource(queue: queue)
            source.schedule(deadline: .now(), repeating: interval)
            source.setEventHandler { sample(into: file) }
            timer = source
            source.resume()
            log.info("memory watch started, writing \(file.path, privacy: .public)")
        }
    }

    static func stop() {
        guard enabled else { return }
        queue.async {
            timer?.cancel()
            timer = nil
        }
    }

    private static func sample(into file: URL) {
        let footprint = footprintBytes()
        // Bytes the process may still allocate before the system kills it. This
        // is the number that matters: the cap is not a documented constant and
        // differs by device and OS, so headroom is measured, not assumed.
        let headroom = UInt64(os_proc_available_memory())
        peak = max(peak, footprint)

        let line = String(
            format: "%7.2fs  footprint %6.1f MB  headroom %6.1f MB  peak %6.1f MB  %@",
            Date().timeIntervalSince(started),
            Double(footprint) / 1_048_576,
            Double(headroom) / 1_048_576,
            Double(peak) / 1_048_576,
            note
        )
        samples.append(line)
        if samples.count > window { samples.removeFirst(samples.count - window) }

        // Rewritten whole rather than appended: an append that is interrupted
        // by the kill can leave a torn last line, and the last line is the one
        // this exists to read.
        let text = samples.joined(separator: "\n") + "\n"
        try? Data(text.utf8).write(to: file, options: .atomic)
    }

    /// `phys_footprint` is the figure the memory limit is enforced against —
    /// resident size is not, and reading resident size is how a process that is
    /// about to be killed can look comfortable.
    private static func footprintBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size
        )
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }
        return info.phys_footprint
    }
}
