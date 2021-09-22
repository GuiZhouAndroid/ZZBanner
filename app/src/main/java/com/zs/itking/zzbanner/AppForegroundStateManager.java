package com.zs.itking.zzbanner;

/**
 * created by on 2021/9/22
 * 描述：
 *
 * @author 1
 * @create 2021-09-22-15:12
 */

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * This class is responsible for tracking all currently open activities.
 * 该类负责跟踪所有当前打开的活动
 * By doing so this class can detect when the application is in the foreground and when it is running in the background.
 * 通过这样做，这个类可以检测应用程序何时在前台，何时在后台运行
 */
public class AppForegroundStateManager {
    private static final String TAG = AppForegroundStateManager.class.getSimpleName();
    private static final int MESSAGE_NOTIFY_LISTENERS = 1;
    public static final long APP_CLOSED_VALIDATION_TIME_IN_MS = 30 * DateUtils.SECOND_IN_MILLIS; // 30 Seconds
    private Reference<Activity> mForegroundActivity;
    private Set<OnAppForegroundStateChangeListener> mListeners = new HashSet<>();
    private AppForegroundState mAppForegroundState = AppForegroundState.NOT_IN_FOREGROUND;
    private NotifyListenersHandler mHandler;

    // Make this class a thread safe singleton
    private static class SingletonHolder {
        public static final AppForegroundStateManager INSTANCE = new AppForegroundStateManager();
    }

    public static AppForegroundStateManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private AppForegroundStateManager() {
        // Create the handler on the main thread
        mHandler = new NotifyListenersHandler(Looper.getMainLooper());
    }

    public enum AppForegroundState {
        IN_FOREGROUND,
        NOT_IN_FOREGROUND
    }

    public interface OnAppForegroundStateChangeListener {
        /** Called when the foreground state of the app changes 当应用程序的前台状态改变时调用 */
        public void onAppForegroundStateChange(AppForegroundState newState);
    }

    /** An activity should call this when it becomes visible 当它变得可见时，活动应该调用它 */
    public void onActivityVisible(Activity activity) {
        if (mForegroundActivity != null) mForegroundActivity.clear();
        mForegroundActivity = new WeakReference<>(activity);
        determineAppForegroundState();
    }

    /** An activity should call this when it is no longer visible 当活动不再可见时，应该调用它 */
    public void onActivityNotVisible(Activity activity) {
        /*
         * The foreground activity may have been replaced with a new foreground activity in our app.
         * So only clear the foregroundActivity if the new activity matches the foreground activity.
         */
        if (mForegroundActivity != null) {
            Activity ref = mForegroundActivity.get();

            if (activity == ref) {
                // This is the activity that is going away, clear the reference
                mForegroundActivity.clear();
                mForegroundActivity = null;
            }
        }

        determineAppForegroundState();
    }

    /** Use to determine if this app is in the foreground 用于确定该应用程序是否在前台 */
    public Boolean isAppInForeground() {
        return mAppForegroundState == AppForegroundState.IN_FOREGROUND;
    }

    /**
     * Call to determine the current state, update the tracking global, and notify subscribers if the state has changed.
     * 调用以确定当前状态，更新跟踪全局，并在状态发生变化时通知订阅者
     */
    private void determineAppForegroundState() {
        /* Get the current state 获取当前状态 */
        AppForegroundState oldState = mAppForegroundState;

        /* Determine what the new state should be 确定新的状态应该是什么 */
        final boolean isInForeground = mForegroundActivity != null && mForegroundActivity.get() != null;
        mAppForegroundState = isInForeground ? AppForegroundState.IN_FOREGROUND : AppForegroundState.NOT_IN_FOREGROUND;

        /** If the new state is different then the old state the notify subscribers of the state change
         *  如果新状态不同，则旧状态通知订阅者状态改变 */
        if (mAppForegroundState != oldState) {
            validateThenNotifyListeners();
        }
    }

    /**
     * Add a listener to be notified of app foreground state change events.
     * 添加一个侦听器，以通知应用程序前台状态改变事件
     *
     * @param listener
     */
    public void addListener(@NonNull OnAppForegroundStateChangeListener listener) {
        mListeners.add(listener);
    }

    /**
     * Remove a listener from being notified of app foreground state change events.
     * 删除应用前台状态改变事件通知的监听器
     *
     * @param listener
     */
    public void removeListener(OnAppForegroundStateChangeListener listener) {
        mListeners.remove(listener);
    }

    /** Notify all listeners the app foreground state has changed
     * 通知所有的监听器应用程序的前台状态已经改变
     */
    private void notifyListeners(AppForegroundState newState) {
        //通知用户应用程序刚刚进入状态
        android.util.Log.i(TAG, "Notifying subscribers that app just entered state: " + newState);

        for (OnAppForegroundStateChangeListener listener : mListeners) {
            listener.onAppForegroundStateChange(newState);
        }
    }

    /**
     * This method will notify subscribes that the foreground state has changed when and if appropriate.
     * 此方法将在适当的时候通知订阅方前台状态已更改
     * We do not want to just notify listeners right away when the app enters of leaves the foreground. When changing orientations or opening and
     * closing the app quickly we briefly pass through a NOT_IN_FOREGROUND state that must be ignored. To accomplish this a delayed message will be
     * Sent when we detect a change. We will not notify that a foreground change happened until the delay time has been reached. If a second
     * foreground change is detected during the delay period then the notification will be canceled.
     *
     * 当应用程序进入或离开前台时，我们不想马上通知侦听器.当改变方向或开放和快速关闭应用程序时，我们会经历一个必须被忽略的not_in_前台状态.为了完成这一点，一个延迟的消息将是
     * 当我们检测到更改时发送。在到达延迟时间之前，我们不会通知前台发生了变化.在到达延迟时间之前，我们不会通知前台发生了变化.如果第二个
     * 在延迟期间检测到前台变化，然后通知将被取消
     */
    private void validateThenNotifyListeners() {
        // If the app has any pending notifications then throw out the event as the state change has failed validation
        // 如果应用程序有任何挂起的通知，那么抛出事件，因为状态更改已经验证失败
        if (mHandler.hasMessages(MESSAGE_NOTIFY_LISTENERS)) {
            //验证失败:抛出应用前台状态更改通知
            android.util.Log.v(TAG, "Validation Failed: Throwing out app foreground state change notification");
            mHandler.removeMessages(MESSAGE_NOTIFY_LISTENERS);
        } else {
            if (mAppForegroundState == AppForegroundState.IN_FOREGROUND) {
                // If the app entered the foreground then notify listeners right away; there is no validation time for this.
                // 如果应用程序进入前台，那么立即通知监听者;没有验证时间
                mHandler.sendEmptyMessage(MESSAGE_NOTIFY_LISTENERS);
            } else {
                // We need to validate that the app entered the background. A delay is used to allow for time when the application went into the
                // background but we do not want to consider the app being backgrounded such as for in app purchasing flow and full screen ads.
                // 我们需要验证应用程序是否进入后台.延迟用于允许应用程序进入时的时间背景，但我们不想考虑应用是背景，如在应用购买流程和全屏广告
                mHandler.sendEmptyMessageDelayed(MESSAGE_NOTIFY_LISTENERS, APP_CLOSED_VALIDATION_TIME_IN_MS);
            }
        }
    }

    private class NotifyListenersHandler extends Handler {
        private NotifyListenersHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message inputMessage) {
            switch (inputMessage.what) {
                // The decoding is done
                // 解码完成了
                case MESSAGE_NOTIFY_LISTENERS:
                    /* Notify subscribers of the state change */
                    /* 通知订阅用户状态更改 */

                    // 应用程序刚刚更改前台状态为
                    android.util.Log.v(TAG, "App just changed foreground state to: " + mAppForegroundState);
                    notifyListeners(mAppForegroundState);
                    break;
                default:
                    super.handleMessage(inputMessage);
            }
        }
    }
}
