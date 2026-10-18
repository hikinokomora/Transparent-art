import Foundation

/// Image-sized filters on single-channel Float planes: box and Gaussian blur, guided filter,
/// connected components and line thickening. Edges are clamped everywhere.
struct Grid {
    let width: Int
    let height: Int

    var size: Int { width * height }

    /// Mean over a (2r+1)² window, O(1) per pixel.
    func box(_ src: [Float], radius r: Int) -> [Float] {
        let window = Double(2 * r + 1)
        var tmp = [Float](repeating: 0, count: size)
        for y in 0..<height {
            let row = y * width
            var acc = 0.0
            for k in -r...r { acc += Double(src[row + clamp(k, 0, width - 1)]) }
            for x in 0..<width {
                tmp[row + x] = Float(acc / window)
                acc += Double(src[row + min(x + r + 1, width - 1)]) - Double(src[row + max(x - r, 0)])
            }
        }
        var out = [Float](repeating: 0, count: size)
        for x in 0..<width {
            var acc = 0.0
            for k in -r...r { acc += Double(tmp[clamp(k, 0, height - 1) * width + x]) }
            for y in 0..<height {
                out[y * width + x] = Float(acc / window)
                acc += Double(tmp[min(y + r + 1, height - 1) * width + x]) - Double(tmp[max(y - r, 0) * width + x])
            }
        }
        return out
    }

    /// Exact Gaussian for small sigmas, three box passes for large ones.
    func blur(_ src: [Float], sigma: Float) -> [Float] {
        if sigma <= 2.5 { return gaussianBlur(src, sigma: sigma) }
        let r = max(1, roundInt(((4 * sigma * sigma + 1).squareRoot() - 1) / 2))
        return box(box(box(src, radius: r), radius: r), radius: r)
    }

    func gaussianBlur(_ src: [Float], sigma: Float) -> [Float] {
        let radius = max(Int((sigma * 3).rounded(.up)), 1)
        var kernel = [Float](repeating: 0, count: radius * 2 + 1)
        var sum: Float = 0
        for i in kernel.indices {
            let x = Float(i - radius)
            kernel[i] = Float(exp(Double(-x * x / (2 * sigma * sigma))))
            sum += kernel[i]
        }
        for i in kernel.indices { kernel[i] /= sum }

        var tmp = [Float](repeating: 0, count: size)
        for y in 0..<height {
            let row = y * width
            for x in 0..<width {
                var acc: Float = 0
                for k in -radius...radius {
                    acc += src[row + clamp(x + k, 0, width - 1)] * kernel[k + radius]
                }
                tmp[row + x] = acc
            }
        }
        var out = [Float](repeating: 0, count: size)
        for y in 0..<height {
            for x in 0..<width {
                var acc: Float = 0
                for k in -radius...radius {
                    acc += tmp[clamp(y + k, 0, height - 1) * width + x] * kernel[k + radius]
                }
                out[y * width + x] = acc
            }
        }
        return out
    }

    /// Edge-preserving filter (He et al.): smooths `src` while following the edges of `guide`.
    func guided(guide: [Float], src: [Float], radius r: Int, eps: Float) -> [Float] {
        let n = size
        let meanI = box(guide, radius: r)
        let meanP = box(src, radius: r)
        var ip = [Float](repeating: 0, count: n)
        var ii = [Float](repeating: 0, count: n)
        for i in 0..<n {
            ip[i] = guide[i] * src[i]
            ii[i] = guide[i] * guide[i]
        }
        let corrIp = box(ip, radius: r)
        let corrII = box(ii, radius: r)
        var a = [Float](repeating: 0, count: n)
        var b = [Float](repeating: 0, count: n)
        for i in 0..<n {
            let varI = corrII[i] - meanI[i] * meanI[i]
            let cov = corrIp[i] - meanI[i] * meanP[i]
            a[i] = cov / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        let meanA = box(a, radius: r)
        let meanB = box(b, radius: r)
        var out = [Float](repeating: 0, count: n)
        for i in 0..<n { out[i] = meanA[i] * guide[i] + meanB[i] }
        return out
    }

    /// Keeps 8-connected components of `mask` that have at least `minSize` pixels and,
    /// when `seeds` is given, contain at least one seed pixel.
    func connectedFilter(mask: [Bool], seeds: [Bool]?, minSize: Float) -> [Bool] {
        var keep = [Bool](repeating: false, count: size)
        var visited = [Bool](repeating: false, count: size)
        var component = [Int](repeating: 0, count: size)
        for start in 0..<size {
            if !mask[start] || visited[start] { continue }
            var count = 0
            var head = 0
            var seeded = seeds == nil
            visited[start] = true
            component[count] = start
            count += 1
            while head < count {
                let p = component[head]
                head += 1
                if !seeded, let s = seeds, s[p] { seeded = true }
                let py = p / width
                let px = p - py * width
                for dy in -1...1 {
                    let ny = py + dy
                    if ny < 0 || ny >= height { continue }
                    for dx in -1...1 {
                        let nx = px + dx
                        if nx < 0 || nx >= width { continue }
                        let q = ny * width + nx
                        if mask[q] && !visited[q] {
                            visited[q] = true
                            component[count] = q
                            count += 1
                        }
                    }
                }
            }
            if seeded && Float(count) >= minSize {
                for k in 0..<count { keep[component[k]] = true }
            }
        }
        return keep
    }

    /// Thickens one-pixel lines with a disc of the given radius.
    func dilate(mask: [Bool], radius: Float) -> [Float] {
        var out = [Float](repeating: 0, count: size)
        if radius < 1 {
            for i in 0..<size where mask[i] { out[i] = 1 }
            return out
        }
        let k = Int(radius.rounded(.up))
        var offsets: [(dy: Int, dx: Int)] = []
        for dy in -k...k {
            for dx in -k...k where Float(dx * dx + dy * dy) <= radius * radius {
                offsets.append((dy, dx))
            }
        }
        for y in 0..<height {
            for x in 0..<width where mask[y * width + x] {
                for o in offsets {
                    let ny = y + o.dy
                    let nx = x + o.dx
                    if ny >= 0 && ny < height && nx >= 0 && nx < width { out[ny * width + nx] = 1 }
                }
            }
        }
        return out
    }
}
