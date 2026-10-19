import Foundation

/// CSS `cubic-bezier(x1, y1, x2, y2)`, solved for y at progress x.
struct CubicBezier {
    let x1, y1, x2, y2: Double

    private func sample(_ a1: Double, _ a2: Double, _ u: Double) -> Double {
        let v = 1 - u
        return 3 * v * v * u * a1 + 3 * v * u * u * a2 + u * u * u
    }

    private func slope(_ a1: Double, _ a2: Double, _ u: Double) -> Double {
        let v = 1 - u
        return 3 * v * v * a1 + 6 * v * u * (a2 - a1) + 3 * u * u * (1 - a2)
    }

    func callAsFunction(_ x: Double) -> Double {
        let x = min(1, max(0, x))
        if x == 0 || x == 1 { return x }
        // Newton from a linear guess, then bisection if the slope flattens.
        var u = x
        for _ in 0..<8 {
            let err = sample(x1, x2, u) - x
            if abs(err) < 1e-9 { return sample(y1, y2, u) }
            let d = slope(x1, x2, u)
            if abs(d) < 1e-7 { break }
            u -= err / d
        }
        var lo = 0.0, hi = 1.0
        u = x
        for _ in 0..<40 {
            let s = sample(x1, x2, u)
            if abs(s - x) < 1e-9 { break }
            if s < x { lo = u } else { hi = u }
            u = (lo + hi) / 2
        }
        return sample(y1, y2, u)
    }
}

/// The film's only two curves. Nothing animates linearly except the
/// terrain's own clock.
enum Ease {
    /// Entrances: fast out of the gate, long soft landing.
    static let out = CubicBezier(x1: 0.22, y1: 1, x2: 0.36, y2: 1)
    /// Camera and scale moves.
    static let inOut = CubicBezier(x1: 0.65, y1: 0, x2: 0.35, y2: 1)
}

func clamp01(_ x: Double) -> Double { min(1, max(0, x)) }
func lerp(_ a: Double, _ b: Double, _ u: Double) -> Double { a + (b - a) * u }

/// Eased progress of a move that starts at `start` and lasts `duration` (seconds).
func progress(_ t: Double, from start: Double, over duration: Double, _ curve: CubicBezier) -> Double {
    curve(clamp01((t - start) / duration))
}
