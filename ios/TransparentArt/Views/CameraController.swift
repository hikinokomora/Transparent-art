import AVFoundation
import SwiftUI
import UIKit

/// Owns the back camera session: permission, start/stop and the flashlight.
final class CameraController: ObservableObject {
    @Published private(set) var status = AVCaptureDevice.authorizationStatus(for: .video)
    @Published private(set) var hasTorch = false

    let session = AVCaptureSession()

    private let queue = DispatchQueue(label: "io.github.transparentart.camera")
    private var configured = false
    private var device: AVCaptureDevice?

    var isAuthorized: Bool { status == .authorized }

    /// Checks the permission (asking for it the first time) and starts the preview when allowed.
    func start() {
        status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            startSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.status = granted ? .authorized : .denied
                    if granted { self.startSession() }
                }
            }
        default:
            break
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.applyTorch(false)
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func setTorch(_ on: Bool) {
        queue.async { [weak self] in self?.applyTorch(on) }
    }

    private func startSession() {
        queue.async { [weak self] in
            guard let self else { return }
            if !self.configured { self.configure() }
            if self.configured && !self.session.isRunning { self.session.startRunning() }
        }
    }

    private func configure() {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .high
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else { return }
        session.addInput(input)
        device = camera
        configured = true

        if (try? camera.lockForConfiguration()) != nil {
            if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
            camera.unlockForConfiguration()
        }
        let torch = camera.hasTorch
        DispatchQueue.main.async { self.hasTorch = torch }
    }

    private func applyTorch(_ on: Bool) {
        guard let camera = device, camera.hasTorch else { return }
        do {
            try camera.lockForConfiguration()
            camera.torchMode = on ? .on : .off
            camera.unlockForConfiguration()
        } catch {}
    }
}

/// Full-screen camera feed. Touches are handled by the overlay above it.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        var previewLayer: AVCaptureVideoPreviewLayer {
            // The layer class is fixed by `layerClass` above.
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}
