import os
import Foundation
import Darwin

/// Structured logging using os.Logger for diagnostics and debugging
enum AppLogger {
    static let wallet = Logger(subsystem: "com.cashu.me", category: "wallet")
    static let network = Logger(subsystem: "com.cashu.me", category: "network")
    static let security = Logger(subsystem: "com.cashu.me", category: "security")
    static let ui = Logger(subsystem: "com.cashu.me", category: "ui")

    /// Point the process's stdout/stderr at a real writable file before any CDK call.
    ///
    /// On iOS the process usually has no valid stderr (fd 2). Rust's `print!`/`eprintln!`
    /// and its panic handler *abort with EIO* ("os error 5") when a write to stderr fails,
    /// and the CDK (cdk-swift) FFI surfaces that as a bogus "failed printing to stderr"
    /// error that masks the real result of an operation (e.g. a mint routing failure).
    /// Giving fd 1 & 2 a writable target makes those writes always succeed, so the real
    /// error propagates instead. Also captures CDK's tracing (`initLogging` → stdout) to a
    /// readable log. Skipped when a debugger is attached so Xcode's console keeps working.
    static func redirectStandardStreamsIfNeeded() {
        guard !isDebuggerAttached() else { return }
        guard let dir = FileManager.default
            .urls(for: .cachesDirectory, in: .userDomainMask).first else { return }
        let logPath = dir.appendingPathComponent("cdk.log").path
        // Truncate stdout per launch so the file stays bounded; stderr shares the same file.
        freopen(logPath, "w", stdout)
        freopen(logPath, "a", stderr)
        setvbuf(stdout, nil, _IOLBF, 0)
        setvbuf(stderr, nil, _IOLBF, 0)
    }

    /// Whether a debugger is currently attached (used to keep the Xcode console live in dev).
    private static func isDebuggerAttached() -> Bool {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        let rc = sysctl(&mib, u_int(mib.count), &info, &size, nil, 0)
        return rc == 0 && (info.kp_proc.p_flag & P_TRACED) != 0
    }
}
