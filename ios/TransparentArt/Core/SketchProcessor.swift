import CoreGraphics
import Foundation

/// Turns a photo into a tracing guide: the ink colour on a transparent background, so it can sit
/// directly on top of the camera feed. Lines come either from the neural network (`NeuralSketcher`)
/// or from the classic filter (`SketchEngine`); shading is shared.
final class SketchProcessor: @unchecked Sendable {
    let width: Int
    let height: Int

    private let source: CGImage
    private let engine: SketchEngine
    private let neural: NeuralSketcher
    private var neuralLines: [NeuralStyle: NeuralLines] = [:]
    private let lock = NSLock()

    enum Failure: Error {
        case imageCreation
    }

    init(image: LoadedImage, neural: NeuralSketcher) {
        self.width = image.width
        self.height = image.height
        self.source = image.cgImage
        self.neural = neural
        self.engine = SketchEngine(argb: image.pixels, width: image.width, height: image.height)
    }

    func render(_ params: SketchParams) throws -> CGImage {
        let argb: [UInt32]
        switch params.mode {
        case .classic:
            argb = engine.render(params)
        case .neural:
            let lines = try linesFor(params.style)
            argb = engine.finish(lines: adjust(lines, params), params: params)
        }
        return try SketchProcessor.makeImage(argb, width: width, height: height)
    }

    /// The network is the slow part, so its raw output is kept per style.
    private func linesFor(_ style: NeuralStyle) throws -> NeuralLines {
        lock.lock()
        defer { lock.unlock() }
        if let cached = neuralLines[style] { return cached }
        let lines = NeuralLines(ink: try neural.draw(source: source, style: style))
        neuralLines[style] = lines
        return lines
    }

    /// Brings the darkest strokes to full ink, then cleanliness drops faint strokes
    /// and line strength makes the remaining ones bolder.
    private func adjust(_ lines: NeuralLines, _ params: SketchParams) -> [Float] {
        let floor = lerp(0.02, 0.45, params.cleanliness)
        let gain = lerp(1, 2.2, params.darkness)
        let gamma = lerp(1.2, 0.6, params.darkness)
        let range = 1 - floor
        var out = [Float](repeating: 0, count: lines.ink.count)
        for i in out.indices {
            let v = clamp((lines.ink[i] / lines.peak - floor) / range, 0, 1)
            out[i] = powf(min(v * gain, 1), gamma)
        }
        return out
    }

    /// 0xAARRGGBB with a constant colour becomes a premultiplied CGImage.
    private static func makeImage(_ argb: [UInt32], width: Int, height: Int) throws -> CGImage {
        var premultiplied = argb
        for i in premultiplied.indices {
            let c = argb[i]
            let alpha = c >> 24
            if alpha == 255 { continue }
            let r = (((c >> 16) & 0xFF) * alpha + 127) / 255
            let g = (((c >> 8) & 0xFF) * alpha + 127) / 255
            let b = ((c & 0xFF) * alpha + 127) / 255
            premultiplied[i] = (alpha << 24) | (r << 16) | (g << 8) | b
        }
        let data = premultiplied.withUnsafeBufferPointer { Data(buffer: $0) }
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        guard let provider = CGDataProvider(data: data as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let image = CGImage(
                  width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                  space: space, bitmapInfo: bitmapInfo, provider: provider, decode: nil,
                  shouldInterpolate: true, intent: .defaultIntent
              ) else { throw Failure.imageCreation }
        return image
    }
}

/// Raw network ink and its 99.5th percentile, which counts as full darkness.
private struct NeuralLines {
    let ink: [Float]
    let peak: Float

    init(ink: [Float]) {
        self.ink = ink
        let bins = 1024
        var histogram = [Int](repeating: 0, count: bins)
        for v in ink { histogram[Int(clamp(v, 0, 1) * Float(bins - 1))] += 1 }
        let target = Float(ink.count) * 0.995
        var acc = 0
        var bin = bins - 1
        for b in 0..<bins {
            acc += histogram[b]
            if Float(acc) >= target {
                bin = b
                break
            }
        }
        self.peak = max(Float(bin) / Float(bins - 1), 0.05)
    }
}
