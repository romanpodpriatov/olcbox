import Darwin
import Foundation
import UIKit

/// Records what the *app* holds, on the transitions that matter.
///
/// The extension is not what runs out of memory. 1.0.416 died 0.1 s after the
/// device reported critical memory pressure, at 34.7 MB of footprint with
/// 15.3 MB of its own allowance still unused, its collector idle and its heap
/// at 17 of 40 MB. Nothing in it was misbehaving; the phone ran short and a
/// packet tunnel provider is an early choice when that happens.
///
/// So the question moved to what else on the phone is holding memory, and the
/// largest thing we control is this process. Instruments measured 136 MB here,
/// of which 119 MB is anonymous VM and 44 MB is IOSurface — graphics, four
/// times the whole extension.
///
/// What Instruments cannot say is whether any of that survives being
/// backgrounded, which is the only state that matters: during a speed test this
/// app is behind Ookla. A suspended app gets no runtime, so it cannot sample
/// itself; two samples bracketing the gap answer it instead. If the figure on
/// the way back in is still near the one on the way out, the memory survived
/// suspension and freeing it is worth doing.
enum AppMemoryWatch {

    private static let appGroup = "group.org.proofkit.app"
    private static let queue = DispatchQueue(label: "org.proofkit.app-memory")
    nonisolated(unsafe) private static var observers: [NSObjectProtocol] = []

    /// Keeps the file to a few kilobytes. Each line is one transition, so this
    /// is dozens of foreground/background cycles.
    private static let window = 40

    static func start() {
        queue.async {
            guard observers.isEmpty else { return }
            let centre = NotificationCenter.default
            let watch: [(NSNotification.Name, String)] = [
                (UIApplication.didEnterBackgroundNotification, "background"),
                (UIApplication.willEnterForegroundNotification, "foreground"),
                (UIApplication.didReceiveMemoryWarningNotification, "memory-warning"),
            ]
            observers = watch.map { name, label in
                centre.addObserver(forName: name, object: nil, queue: nil) { _ in
                    record(label)
                }
            }
            record("launch")
        }
    }

    private static func record(_ event: String) {
        let line = String(
            format: "%@  app footprint %6.1f MB  %@",
            ISO8601DateFormatter().string(from: Date()),
            Double(footprintBytes()) / 1_048_576,
            event
        )
        queue.async {
            guard let container = FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: appGroup
            ) else { return }
            let file = container.appendingPathComponent("app-memory.txt")
            var lines = (try? String(contentsOf: file, encoding: .utf8))?
                .split(separator: "\n")
                .map(String.init) ?? []
            lines.append(line)
            if lines.count > window { lines.removeFirst(lines.count - window) }
            try? Data((lines.joined(separator: "\n") + "\n").utf8)
                .write(to: file, options: .atomic)
        }
    }

    /// `phys_footprint`, the same figure the system enforces limits against and
    /// the same one the extension's own trace reports, so the two are
    /// comparable.
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
