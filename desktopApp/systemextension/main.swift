// The entry point, without which the extension is not an extension.
//
// A NetworkExtension system extension is an ordinary executable, and macOS does
// not call its provider class directly: the process must call
// `NEProvider.startSystemExtensionMode()`, which registers the classes listed
// under NEProviderClasses in Info.plist and hands the process to the system.
// Then it has to stay alive, which is what dispatchMain() is for.
//
// Leaving this out does not fail anything a build can see. The bundle is still a
// bundle, the binary is still a valid Mach-O, codesign is happy, notarisation is
// happy — and every check in the packaging step passed, because they all ask
// whether the extension is present and correctly formed, not whether it does
// anything. swiftc compiled a class and no top-level code into an executable
// whose main returns immediately. macOS scanned it, found no provider, and said
// `OSSystemExtensionError code 4: extension not found` — about a bundle that was
// demonstrably there.
//
// Top-level code belongs in a file called main.swift and nowhere else once more
// than one file is compiled, which is why this is its own file rather than a few
// lines at the bottom of the provider.
import Foundation
import NetworkExtension

autoreleasepool {
    NEProvider.startSystemExtensionMode()
}

dispatchMain()
