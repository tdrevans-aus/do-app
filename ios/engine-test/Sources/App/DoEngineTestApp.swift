import SwiftUI
import UserNotifications

@main
struct DoEngineTestApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(Engine.shared)
                .environmentObject(Engine.shared.store)
                .environmentObject(Engine.shared.alarms)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await Engine.shared.replan(reason: "app opened") }
            }
        }
        .backgroundTask(.appRefresh(Engine.refreshTaskID)) {
            await Engine.shared.runBackgroundRefresh()
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Set before launch finishes so actions that launch the app in the
        // background are delivered.
        UNUserNotificationCenter.current().delegate = NotificationDelegate.shared
        Task { @MainActor in
            Engine.shared.start(reason: "didFinishLaunching")
        }
        return true
    }
}

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationDelegate()

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let id = notification.request.identifier
        await Engine.shared.notificationWillPresent(identifier: id)
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let id = response.notification.request.identifier
        let action = response.actionIdentifier
        await Engine.shared.notificationResponse(identifier: id, action: action)
    }
}
