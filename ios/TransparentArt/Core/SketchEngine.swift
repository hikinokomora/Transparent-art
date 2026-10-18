import Foundation

/// Photo → tracing guide in plain Swift, a port of the Android `SketchEngine`.
///
/// Pipeline:
///  1. CIELAB, so borders between colours of equal brightness are still found.
///  2. Auto levels + auto gamma, so dark or washed-out photos behave like good ones.
///  3. Edge-preserving smoothing (guided filter) to drop noise and fine texture.
///  4. Colour gradient from the structure tensor, thinned by non-maximum suppression.
///  5. Surround suppression: edges inside busy texture (fur, grass, wood) fade out.
///  6. Canny-style hysteresis with adaptive thresholds, short fragments removed.
///  7. Thin dark details (eyes, lips, whiskers) from a one-sided difference of Gaussians.
///  8. A few flat shading zones for the darker part of the tonal range
///     (also applied on top of the neural lines, see `finish`).
///
/// Results are tuned for images whose longest side is about 1000–1300 px.
final class SketchEngine {
    let width: Int
    let height: Int

    private let grid: Grid
    private let size: Int
    private let scale: Float
    private let lum: [Float]
    private let chromaA: [Float]
    private let chromaB: [Float]
    private let tone: [Float]
    private let darkDog: [Float]

    private let lock = NSLock()
    private var edgeKey = -1
    private var edgeCache: [Float]?

    /// `argb` holds one 0xAARRGGBB pixel per entry, row by row. Transparent areas count as white paper.
    init(argb: [UInt32], width: Int, height: Int) {
        let grid = Grid(width: width, height: height)
        let scale = Float(max(width, height)) / 1000
        let lab = SketchEngine.toLab(argb, count: width * height)
        var l = lab.l
        SketchEngine.normalize(&l)

        let narrow = grid.blur(l, sigma: scale)
        let wide = grid.blur(l, sigma: scale * 2.5)
        var dog = [Float](repeating: 0, count: width * height)
        for i in dog.indices { dog[i] = wide[i] - narrow[i] }

        let smooth = grid.guided(guide: l, src: l, radius: max(2, roundInt(14 * scale)), eps: 0.02)

        self.width = width
        self.height = height
        self.grid = grid
        self.size = width * height
        self.scale = scale
        self.lum = l
        self.chromaA = lab.a
        self.chromaB = lab.b
        self.darkDog = dog
        self.tone = grid.blur(smooth, sigma: 2.5 * scale)
    }

    /// Classic outlines plus shading, as 0xAARRGGBB pixels in the ink colour.
    func render(_ params: SketchParams) -> [UInt32] {
        lock.lock()
        defer { lock.unlock() }
        return finish(lines: classicLines(params), params: params)
    }

    /// Adds the shading zones to `lines` (ink darkness 0...1) and returns 0xAARRGGBB pixels
    /// in the ink colour on a transparent background.
    func finish(lines: [Float], params: SketchParams) -> [UInt32] {
        let levels = params.shadeLevels
        let rgb = params.inkColor & 0x00FF_FFFF
        var out = [UInt32](repeating: 0, count: size)
        for i in 0..<size {
            var ink = lines[i]
            if levels >= 2 {
                let darkness = clamp((0.8 - tone[i]) / 0.8, 0, 1)
                let step = min((darkness * Float(levels)).rounded(.down), Float(levels - 1)) / Float(levels - 1)
                ink = max(ink, step * params.shadeStrength)
            }
            let alpha = UInt32(roundInt(clamp(ink, 0, 1) * 255))
            out[i] = (alpha << 24) | rgb
        }
        return out
    }

    private func classicLines(_ params: SketchParams) -> [Float] {
        let detail = clamp(params.detail, 0, 1)
        let sensitivity = clamp(params.sensitivity, 0, 1)
        let minLength = lerp(40, 10, detail) * scale

        // Outlines.
        let strength = edgeStrength(detail: detail)
        let high = max(quantileOfPositive(strength, 1 - lerp(0.05, 0.22, sensitivity)), 0.02)
        let low = high * 0.4
        let weak = strength.map { $0 >= low }
        let seeds = strength.map { $0 >= high }
        let edges = grid.connectedFilter(mask: weak, seeds: seeds, minSize: minLength)

        var ink = grid.dilate(mask: edges, radius: lerp(0.8, 3.2, params.thickness) * scale)
        ink = grid.blur(ink, sigma: 0.7)
        for i in 0..<size { ink[i] = min(ink[i] * 1.3, 1) }

        // Thin dark details.
        let t = lerp(0.06, 0.02, sensitivity)
        let dark = darkDog.map { clamp(($0 - t) / t, 0, 1) }
        let darkKeep = grid.connectedFilter(mask: dark.map { $0 > 0.25 }, seeds: nil, minSize: minLength * 1.5)
        for i in 0..<size where darkKeep[i] {
            ink[i] = max(ink[i], dark[i] * 0.9)
        }
        return ink
    }

    /// Thinned, texture-suppressed colour gradient. Depends only on `detail`, so it is cached.
    private func edgeStrength(detail: Float) -> [Float] {
        let key = roundInt(detail * 100)
        if let cached = edgeCache, key == edgeKey { return cached }

        let radius = max(1, roundInt(lerp(9, 2, detail) * scale))
        let eps = lerp(0.02, 0.004, detail)
        let channels: [(plane: [Float], weight: Float)] = [
            (grid.guided(guide: lum, src: lum, radius: radius, eps: eps), 1),
            (grid.guided(guide: lum, src: chromaA, radius: radius, eps: eps), 1.5),
            (grid.guided(guide: lum, src: chromaB, radius: radius, eps: eps), 1.5),
        ]

        // Structure tensor summed over channels.
        var jxx = [Float](repeating: 0, count: size)
        var jyy = [Float](repeating: 0, count: size)
        var jxy = [Float](repeating: 0, count: size)
        for (c, weight) in channels {
            for y in 0..<height {
                let ym = max(y - 1, 0) * width
                let y0 = y * width
                let yp = min(y + 1, height - 1) * width
                for x in 0..<width {
                    let xm = max(x - 1, 0)
                    let xp = min(x + 1, width - 1)
                    let gx = ((c[ym + xp] + 2 * c[y0 + xp] + c[yp + xp]) -
                        (c[ym + xm] + 2 * c[y0 + xm] + c[yp + xm])) / 8
                    let gy = ((c[yp + xm] + 2 * c[yp + x] + c[yp + xp]) -
                        (c[ym + xm] + 2 * c[ym + x] + c[ym + xp])) / 8
                    let i = y0 + x
                    jxx[i] += weight * gx * gx
                    jyy[i] += weight * gy * gy
                    jxy[i] += weight * gx * gy
                }
            }
        }

        var magnitude = [Float](repeating: 0, count: size)
        var direction = [UInt8](repeating: 0, count: size)
        for i in 0..<size {
            let d = jxx[i] - jyy[i]
            let root = (d * d + 4 * jxy[i] * jxy[i]).squareRoot()
            magnitude[i] = max((jxx[i] + jyy[i] + root) / 2, 0).squareRoot()
            let theta = 0.5 * atan2(2.0 * Double(jxy[i]), Double(d))
            var degrees = theta * 180 / Double.pi
            if degrees < 0 { degrees += 180 }
            direction[i] = UInt8(Int((degrees / 45).rounded()) % 4)
        }

        // Non-maximum suppression across the edge direction.
        let dyOffset = [0, 1, 1, 1]
        let dxOffset = [1, 1, 0, -1]
        var thin = [Float](repeating: 0, count: size)
        for y in 0..<height {
            for x in 0..<width {
                let i = y * width + x
                let m = magnitude[i]
                if m <= 0 { continue }
                let k = Int(direction[i])
                let y1 = clamp(y + dyOffset[k], 0, height - 1)
                let x1 = clamp(x + dxOffset[k], 0, width - 1)
                let y2 = clamp(y - dyOffset[k], 0, height - 1)
                let x2 = clamp(x - dxOffset[k], 0, width - 1)
                if m >= magnitude[y1 * width + x1] && m >= magnitude[y2 * width + x2] { thin[i] = m }
            }
        }

        // Surround suppression: a lone contour has low edge density around it, texture has high.
        let density = grid.blur(thin, sigma: 6 * scale)
        let beta = lerp(5, 1.5, detail)
        for i in 0..<size where thin[i] > 0 {
            thin[i] = max(thin[i] - beta * density[i], 0)
        }

        edgeKey = key
        edgeCache = thin
        return thin
    }

    private static func toLab(_ argb: [UInt32], count: Int) -> (l: [Float], a: [Float], b: [Float]) {
        var linear = [Float](repeating: 0, count: 256)
        for i in 0..<256 {
            let c = Float(i) / 255
            linear[i] = c <= 0.04045 ? c / 12.92 : Float(pow(Double((c + 0.055) / 1.055), 2.4))
        }
        func f(_ t: Float) -> Float {
            t > 0.008856 ? Float(cbrt(Double(t))) : 7.787 * t + 16 / 116
        }

        var l = [Float](repeating: 0, count: count)
        var ca = [Float](repeating: 0, count: count)
        var cb = [Float](repeating: 0, count: count)
        for i in 0..<count {
            let c = argb[i]
            let alpha = Int(c >> 24)
            // Composite onto white paper.
            let ri = (Int((c >> 16) & 0xFF) * alpha + 255 * (255 - alpha) + 127) / 255
            let gi = (Int((c >> 8) & 0xFF) * alpha + 255 * (255 - alpha) + 127) / 255
            let bi = (Int(c & 0xFF) * alpha + 255 * (255 - alpha) + 127) / 255
            let r = linear[ri]
            let g = linear[gi]
            let b = linear[bi]
            let fx = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047)
            let fy = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
            let fz = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
            l[i] = 1.16 * fy - 0.16
            ca[i] = 5 * (fx - fy)
            cb[i] = 2 * (fy - fz)
        }
        return (l, ca, cb)
    }

    /// Stretches the 1st...99th percentile to 0...1, then applies a gamma that moves the median to 0.5.
    private static func normalize(_ values: inout [Float]) {
        let bins = 4096
        let count = values.count
        var histogram = [Int](repeating: 0, count: bins)
        for v in values { histogram[roundInt(clamp(v, 0, 1) * Float(bins - 1))] += 1 }

        func percentile(_ p: Float) -> Float {
            let target = p * Float(count)
            var acc = 0
            for b in 0..<bins {
                acc += histogram[b]
                if Float(acc) >= target { return Float(b) / Float(bins - 1) }
            }
            return 1
        }
        let lo = percentile(0.01)
        let hi = percentile(0.99)
        let range = max(hi - lo, 1e-3)
        let median = clamp((percentile(0.5) - lo) / range, 0.05, 0.95)
        let gamma = clamp(log(0.5) / log(Double(median)), 0.5, 2.0)
        var curve = [Float](repeating: 0, count: bins)
        for i in 0..<bins { curve[i] = Float(pow(Double(i) / Double(bins - 1), gamma)) }
        for i in 0..<count {
            let v = clamp((values[i] - lo) / range, 0, 1)
            values[i] = curve[roundInt(v * Float(bins - 1))]
        }
    }

    /// Value below which the fraction `q` of the positive entries fall.
    private func quantileOfPositive(_ values: [Float], _ q: Float) -> Float {
        var top: Float = 0
        var count = 0
        for v in values where v > 0 {
            count += 1
            if v > top { top = v }
        }
        if count == 0 { return 1 }
        let bins = 4096
        var histogram = [Int](repeating: 0, count: bins)
        for v in values where v > 0 { histogram[min(Int(v / top * Float(bins)), bins - 1)] += 1 }
        let target = q * Float(count)
        var acc = 0
        for b in 0..<bins {
            acc += histogram[b]
            if Float(acc) >= target { return (Float(b) + 1) / Float(bins) * top }
        }
        return top
    }
}
