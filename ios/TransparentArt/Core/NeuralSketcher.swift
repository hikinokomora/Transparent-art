import CoreGraphics
import Foundation
import OnnxRuntimeBindings

/// Runs the Informative Drawings generator (Chan, Durand, Isola, CVPR 2022, MIT License) on the
/// device with ONNX Runtime. The network is slow at full resolution, so it sees a reduced copy
/// of the photo and the drawing is scaled back up afterwards. The models are the same files
/// as in the Android app.
final class NeuralSketcher: @unchecked Sendable {
    /// Longest side the network works at: a balance between line detail and speed on phones.
    static let workSide = 640

    enum Failure: Error {
        case modelMissing(String)
        case badOutput
    }

    private let lock = NSLock()
    private var env: ORTEnv?
    private var sessions: [NeuralStyle: ORTSession] = [:]

    /// Returns ink darkness 0...1 for every pixel of `source` (row by row, source size).
    func draw(source: CGImage, style: NeuralStyle, workSide: Int = NeuralSketcher.workSide) throws -> [Float] {
        lock.lock()
        defer { lock.unlock() }

        let factor = Float(workSide) / Float(max(source.width, source.height))
        // The network downsamples twice, so its input must be a multiple of 4.
        let w = max(4, roundInt(Float(source.width) * factor / 4) * 4)
        let h = max(4, roundInt(Float(source.height) * factor / 4) * 4)
        let plane = w * h

        var rgba = [UInt8](repeating: 255, count: plane * 4)
        var drawn = false
        rgba.withUnsafeMutableBytes { buffer in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(
                      data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8,
                      bytesPerRow: w * 4, space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
                  ) else { return }
            context.interpolationQuality = .high
            context.draw(source, in: CGRect(x: 0, y: 0, width: w, height: h))
            drawn = true
        }
        guard drawn else { throw Failure.badOutput }

        var input = [Float](repeating: 0, count: 3 * plane)
        for i in 0..<plane {
            input[i] = Float(rgba[i * 4]) / 255
            input[plane + i] = Float(rgba[i * 4 + 1]) / 255
            input[2 * plane + i] = Float(rgba[i * 4 + 2]) / 255
        }
        let bytes = input.withUnsafeBufferPointer {
            NSMutableData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<Float>.stride)
        }
        let tensor = try ORTValue(
            tensorData: bytes, elementType: .float, shape: [1, 3, NSNumber(value: h), NSNumber(value: w)]
        )

        let model = try loadSession(for: style)
        let inputName = try model.inputNames().first ?? "image"
        let outputName = try model.outputNames().first ?? "drawing"
        let outputs = try model.run(withInputs: [inputName: tensor], outputNames: [outputName], runOptions: nil)
        guard let result = outputs[outputName] else { throw Failure.badOutput }
        let raw = try result.tensorData() as Data
        guard raw.count >= plane * MemoryLayout<Float>.stride else { throw Failure.badOutput }

        var drawing = [Float](repeating: 0, count: plane)
        drawing.withUnsafeMutableBufferPointer { _ = raw.copyBytes(to: $0, from: 0..<plane * MemoryLayout<Float>.stride) }
        // The model draws dark lines on white; turn that into ink amount.
        for i in 0..<plane { drawing[i] = clamp(1 - drawing[i], 0, 1) }
        return NeuralSketcher.upscale(drawing, sourceWidth: w, sourceHeight: h, width: source.width, height: source.height)
    }

    private func loadSession(for style: NeuralStyle) throws -> ORTSession {
        if let existing = sessions[style] { return existing }
        let environment: ORTEnv
        if let existing = env {
            environment = existing
        } else {
            environment = try ORTEnv(loggingLevel: .error)
            env = environment
        }
        guard let path = Bundle.main.path(forResource: style.resource, ofType: "onnx", inDirectory: "models") else {
            throw Failure.modelMissing(style.resource)
        }
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(Int32(min(4, max(1, ProcessInfo.processInfo.activeProcessorCount))))
        try options.setGraphOptimizationLevel(.all)
        let session = try ORTSession(env: environment, modelPath: path, sessionOptions: options)
        sessions[style] = session
        return session
    }

    /// Bilinear resize of a single-channel image.
    static func upscale(_ src: [Float], sourceWidth sw: Int, sourceHeight sh: Int, width dw: Int, height dh: Int) -> [Float] {
        var out = [Float](repeating: 0, count: dw * dh)
        let sx = Float(sw) / Float(dw)
        let sy = Float(sh) / Float(dh)
        for y in 0..<dh {
            let fy = clamp((Float(y) + 0.5) * sy - 0.5, 0, Float(sh - 1))
            let y0 = Int(fy)
            let y1 = min(y0 + 1, sh - 1)
            let ty = fy - Float(y0)
            for x in 0..<dw {
                let fx = clamp((Float(x) + 0.5) * sx - 0.5, 0, Float(sw - 1))
                let x0 = Int(fx)
                let x1 = min(x0 + 1, sw - 1)
                let tx = fx - Float(x0)
                let top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                let bottom = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                out[y * dw + x] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }
}
