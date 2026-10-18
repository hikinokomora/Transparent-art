import Foundation

enum SketchMode: String, CaseIterable, Identifiable {
    case neural
    case classic

    var id: String { rawValue }
}

/// Line drawing styles of the Informative Drawings model.
enum NeuralStyle: String, CaseIterable, Identifiable {
    case contour
    case sketch

    var id: String { rawValue }
    var resource: String { "informative_drawings_\(rawValue)" }
}

/// Ink colours as 0xRRGGBB.
let inkColors: [UInt32] = [0x000000, 0xE53935, 0x1E88E5, 0x43A047]

/// All sliders are normalised to 0...1 so the UI does not need to know the maths.
struct SketchParams: Equatable {
    var mode: SketchMode = .neural

    // Neural mode.
    var style: NeuralStyle = .contour
    var cleanliness: Float = 0.4
    var darkness: Float = 0.5

    // Classic mode.
    var detail: Float = 0.5
    var sensitivity: Float = 0.5
    var thickness: Float = 0.3

    // Both modes. 0 means no shading, otherwise the number of tones (2...5).
    var shadeLevels: Int = 0
    var shadeStrength: Float = 0.3
    var inkColor: UInt32 = inkColors[0]
}

@inline(__always)
func lerp(_ a: Float, _ b: Float, _ t: Float) -> Float {
    a + (b - a) * min(max(t, 0), 1)
}

/// Rounds half up, like Kotlin's `roundToInt`.
@inline(__always)
func roundInt(_ x: Float) -> Int {
    Int((x + 0.5).rounded(.down))
}

@inline(__always)
func clamp<T: Comparable>(_ value: T, _ low: T, _ high: T) -> T {
    min(max(value, low), high)
}
