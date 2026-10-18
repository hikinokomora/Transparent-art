import SwiftUI
import UIKit

/// The sketch laid over the live camera. Drag, pinch and twist move the picture; a tap hides the controls.
struct TraceView: View {
    let sketch: UIImage
    @Binding var trace: TraceState
    let onBack: () -> Void

    @StateObject private var camera = CameraController()
    @Environment(\.scenePhase) private var scenePhase

    @State private var controlsVisible = true
    @State private var overlayVisible = true
    @State private var torchOn = false

    @GestureState private var dragDelta: CGSize = .zero
    @GestureState private var magnifyDelta: CGFloat = 1
    @GestureState private var rotateDelta: Angle = .zero

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if camera.isAuthorized {
                CameraPreview(session: camera.session).ignoresSafeArea()
            }

            if overlayVisible {
                Image(uiImage: sketch)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .scaleEffect(
                        x: (trace.flipped ? -1 : 1) * trace.scale * magnifyDelta,
                        y: trace.scale * magnifyDelta
                    )
                    .rotationEffect(trace.rotation + rotateDelta)
                    .offset(
                        x: trace.offset.width + dragDelta.width,
                        y: trace.offset.height + dragDelta.height
                    )
                    .opacity(trace.alpha)
                    .allowsHitTesting(false)
                    .ignoresSafeArea()
            }

            gestureLayer

            if !camera.isAuthorized && camera.status != .notDetermined {
                PermissionRequest()
            }

            if controlsVisible {
                VStack(spacing: 0) {
                    HStack {
                        Button(action: onBack) {
                            Image(systemName: "chevron.left")
                                .font(.title3.weight(.semibold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.black.opacity(0.45), in: Circle())
                                .accessibilityLabel(Text("back"))
                        }
                        Spacer()
                    }
                    .padding(8)
                    Spacer()
                    controls
                }
                .transition(.opacity)
            }
        }
        .statusBarHidden(true)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            camera.start()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            camera.stop()
        }
        .onChange(of: scenePhase) { phase in
            // The permission may have been granted in system settings.
            if phase == .active { camera.start() }
        }
        .onChange(of: torchOn) { camera.setTorch($0) }
    }

    /// Transparent layer that receives all touches, so the overlay can be moved from anywhere.
    private var gestureLayer: some View {
        Color.clear
            .contentShape(Rectangle())
            .ignoresSafeArea()
            .gesture(drag, including: trace.locked ? .subviews : .all)
            .simultaneousGesture(magnify, including: trace.locked ? .subviews : .all)
            .simultaneousGesture(rotate, including: trace.locked ? .subviews : .all)
            .simultaneousGesture(
                TapGesture().onEnded {
                    withAnimation { controlsVisible.toggle() }
                }
            )
    }

    private var drag: some Gesture {
        DragGesture()
            .updating($dragDelta) { value, state, _ in state = value.translation }
            .onEnded { value in
                trace.offset.width += value.translation.width
                trace.offset.height += value.translation.height
            }
    }

    private var magnify: some Gesture {
        MagnificationGesture()
            .updating($magnifyDelta) { value, state, _ in state = value }
            .onEnded { value in
                trace.scale = min(max(trace.scale * value, 0.1), 10)
            }
    }

    private var rotate: some Gesture {
        RotationGesture()
            .updating($rotateDelta) { value, state, _ in state = value }
            .onEnded { value in trace.rotation += value }
    }

    private var controls: some View {
        VStack(spacing: 4) {
            HStack(spacing: 12) {
                Image(systemName: "circle.lefthalf.filled")
                    .foregroundColor(.white)
                    .accessibilityLabel(Text("opacity"))
                Slider(value: $trace.alpha, in: 0.05...1)
            }
            HStack {
                toolButton("lock", system: trace.locked ? "lock.fill" : "lock.open.fill", active: trace.locked) {
                    trace.locked.toggle()
                }
                toolButton("flip", system: "flip.horizontal", active: trace.flipped, enabled: !trace.locked) {
                    trace.flipped.toggle()
                }
                toolButton(
                    "toggle_overlay",
                    system: overlayVisible ? "eye.fill" : "eye.slash.fill",
                    active: !overlayVisible
                ) {
                    overlayVisible.toggle()
                }
                toolButton(
                    "torch",
                    system: torchOn ? "flashlight.on.fill" : "flashlight.off.fill",
                    active: torchOn,
                    enabled: camera.hasTorch
                ) {
                    torchOn.toggle()
                }
                toolButton("reset", system: "arrow.counterclockwise", active: false, enabled: !trace.locked) {
                    var fresh = TraceState()
                    fresh.alpha = trace.alpha
                    trace = fresh
                }
            }
            Text("tap_to_hide")
                .font(.footnote)
                .foregroundColor(.white.opacity(0.7))
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.55).ignoresSafeArea(edges: .bottom))
    }

    private func toolButton(
        _ label: LocalizedStringKey,
        system: String,
        active: Bool,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.title3)
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundColor(.white.opacity(enabled ? 1 : 0.3))
                .background(active ? Color.accentColor : Color.clear, in: Circle())
                .accessibilityLabel(Text(label))
        }
        .disabled(!enabled)
    }
}

private struct PermissionRequest: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("camera_permission_needed")
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
            Button("open_settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(32)
    }
}
