/**
 * Copyright (c) 2025, The LineageOS Project
 * Copyright (c) 2024-2026 Lunaris AOSP
 * Copyright (c) 2025-2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Notification;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.res.R;
import com.android.systemui.util.IconFetcher;
import com.android.systemui.statusbar.OnGoingActionProgressGroup;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.MediaSessionManagerHelper;

import com.android.internal.util.lunaris.VibrationUtils;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnGoingActionProgressController implements NotificationListener.NotificationHandler,
        KeyguardStateController.Callback, OnHeadsUpChangedListener {
    private static final String TAG = "OngoingActionProgressController";
    private static final String ONGOING_ACTION_CHIP_ENABLED = "ongoing_action_chip";
    private static final String SHOW_MEDIA_PROGRESS = "show_media_progress";
    private static final String PROGRESS_BAR_OPACITY = "progress_bar_opacity";
    private static final String COMPACT_MODE_ENABLED = "compact_progress_mode";
    
    private static final String CHIP_POSITION_X = "chip_position_x";
    private static final String CHIP_POSITION_Y = "chip_position_y";
    private static final String CHIP_WIDTH = "chip_width";
    private static final String CHIP_HEIGHT = "chip_height";
    private static final String CIRCULAR_CHIP_SIZE = "circular_chip_size";
    private static final String CIRCULAR_POSITION_X = "circular_position_x";
    private static final String CIRCULAR_POSITION_Y = "circular_position_y";
    
    private static final int SWIPE_THRESHOLD = 100;
    private static final int SWIPE_VELOCITY_THRESHOLD = 100;
    private static final int DEFAULT_OPACITY = 255;
    private static final int DEFAULT_OPACITY_PERCENTAGE = 100;
    private static final int MEDIA_UPDATE_INTERVAL_MS = 1000;
    private static final int DEBOUNCE_DELAY_MS = 150;
    private static final int STALE_PROGRESS_CHECK_INTERVAL_MS = 5000;
    private static final int PROGRESS_TIMEOUT_MS = 30000;
    
    private static final int DEFAULT_CHIP_WIDTH = 86;
    private static final int DEFAULT_CHIP_HEIGHT = 26;
    private static final int DEFAULT_CIRCULAR_SIZE = 28;
    private static final int DEFAULT_POSITION_X = 0;
    private static final int DEFAULT_POSITION_Y = 0;
    
    private static final int MIN_CHIP_WIDTH = 60;
    private static final int MAX_CHIP_WIDTH = 200;
    private static final int MIN_CHIP_HEIGHT = 20;
    private static final int MAX_CHIP_HEIGHT = 50;
    private static final int MIN_CIRCULAR_SIZE = 20;
    private static final int MAX_CIRCULAR_SIZE = 100;
    
    private static final int MAX_ICON_CACHE_SIZE = 20;
    
    private static final int PROGRESS_ANIMATION_DURATION = 300;
    private static final int ENTRY_ANIMATION_DURATION = 400;
    private static final int EXIT_ANIMATION_DURATION = 300;
    private static final int EXPAND_ANIMATION_DURATION = 350;
    private static final float ENTRY_TRANSLATION_Y = 50f;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 1000;

    public interface StateCallback {
        void onStateChanged(
            boolean isVisible,
            int progress,
            int maxProgress,
            Drawable icon,
            boolean isIconAdaptive,
            String packageName,
            boolean isCompactMode,
            float opacity,
            boolean showMediaControls,
            int chipWidth,
            int chipHeight,
            int chipPositionX,
            int chipPositionY,
            int circularSize,
            int circularPositionX,
            int circularPositionY);
    }

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationListener mNotificationListener;
    private final HeadsUpManager mHeadsUpManager;
    private final IconFetcher mIconFetcher;
    private final MediaSessionManagerHelper mMediaSessionHelper;
    private final ExecutorService mBackgroundExecutor;
    private StateCallback mStateCallback = null;
    private final boolean mIsComposeMode;
    private final Object mLock = new Object();
    private final Runnable mUiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                mUpdatePending = false;
                mLastUpdateTime = System.currentTimeMillis();
                updateViews();
            }
        }
    };

    private final ProgressBar mProgressBar;
    private final ProgressBar mCircularProgressBar;
    private final View mProgressRootView;
    private final View mCompactRootView;
    private final ImageView mIconView;
    private final ImageView mCompactIconView;

    private final HashMap<String, IconFetcher.AdaptiveDrawableResult> mIconCache = new HashMap<>();
    
    // Animation related
    private ObjectAnimator mProgressAnimator;
    private ObjectAnimator mCircularProgressAnimator;
    private int mAccentColor;
    private final AtomicBoolean mIsAnimatingEntry = new AtomicBoolean(false);
    private final AtomicBoolean mIsAnimatingExit = new AtomicBoolean(false);
    
    private boolean mShowMediaProgress = true;
    private boolean mIsTrackingProgress = false;
    private boolean mIsForceHidden = false;
    private boolean mHeadsUpPinned = false;
    private long mLastProgressUpdateTime = 0;
    private boolean mIsEnabled;
    private boolean mIsCompactModeEnabled = false;
    private int mCurrentProgress = 0;
    private int mCurrentProgressMax = 0;
    private Drawable mCurrentIcon = null;
    private boolean mCurrentIconIsAdaptive = false;
    private int mProgressBarOpacity = DEFAULT_OPACITY;
    private boolean mIsMenuVisible = false;
    private boolean mIsSystemChipVisible = false;

    private int mChipWidth = DEFAULT_CHIP_WIDTH;
    private int mChipHeight = DEFAULT_CHIP_HEIGHT;
    private int mChipPositionX = DEFAULT_POSITION_X;
    private int mChipPositionY = DEFAULT_POSITION_Y;
    private int mCircularChipSize = DEFAULT_CIRCULAR_SIZE;
    private int mCircularPositionX = DEFAULT_POSITION_X;
    private int mCircularPositionY = DEFAULT_POSITION_Y;

    private String mTrackedNotificationKey;
    private String mTrackedPackageName;
    private PopupWindow mMediaPopup;
    private boolean mIsPopupActive = false;
    private volatile boolean mNeedsFullUiUpdate = true;
    private volatile boolean mIsViewAttached = false;
    private boolean mIsExpanded = false;
    
    private boolean mUpdatePending = false;
    private long mLastUpdateTime = 0;
    
    private String mLastLoadedIconPackage = null;
    private String mLastLoadedCompactIconPackage = null;

    private final GestureDetector mGestureDetector;
    private final Handler mMediaProgressHandler = new Handler(Looper.getMainLooper());
    private final Runnable mMediaProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
                updateMediaProgressOnly();
                mMediaProgressHandler.postDelayed(this, MEDIA_UPDATE_INTERVAL_MS);
            }
        }
    };

    private final Runnable mStaleProgressChecker = new Runnable() {
        @Override
        public void run() {
            synchronized (OnGoingActionProgressController.this) {
                checkForStaleProgress();
            }
            if (mIsViewAttached) {
                mHandler.postDelayed(this, STALE_PROGRESS_CHECK_INTERVAL_MS);
            }
        }
    };

    private final Runnable mCompactCollapseRunnable = () -> {
        if (mIsCompactModeEnabled && mIsExpanded) {
            mIsExpanded = false;
            requestUiUpdate();
        }
    };

    private final Runnable mMenuCollapseRunnable = () -> {
        mIsMenuVisible = false;
        notifyStateCallback();
    };

    private final MediaSessionManagerHelper.MediaMetadataListener mMediaMetadataListener = 
            new MediaSessionManagerHelper.MediaMetadataListener() {
                @Override
                public void onMediaMetadataChanged() {
                    mNeedsFullUiUpdate = true;
                    requestUiUpdate();
                }

                @Override
                public void onPlaybackStateChanged() {
                    mNeedsFullUiUpdate = true;
                    requestUiUpdate();
                }
            };

    public OnGoingActionProgressController(
            Context context, OnGoingActionProgressGroup progressGroup,
            NotificationListener notificationListener, KeyguardStateController keyguardStateController,
            HeadsUpManager headsUpManager) {

        mIsComposeMode = (progressGroup.rootView == null && progressGroup.compactRootView == null);

        if (progressGroup == null) {
            Log.wtf(TAG, "progressGroup is null");
            throw new IllegalArgumentException("progressGroup cannot be null");
        }
        
        mNotificationListener = notificationListener;
        if (mNotificationListener == null) {
            Log.wtf(TAG, "mNotificationListener is null");
            throw new IllegalArgumentException("notificationListener cannot be null");
        }

        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManager;
        mContext = context;
        mContentResolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        mSettingsObserver = new SettingsObserver(mHandler);
        mBackgroundExecutor = Executors.newSingleThreadExecutor();

        mProgressBar = progressGroup.progressBarView;
        mCircularProgressBar = progressGroup.circularProgressBarView;
        mProgressRootView = progressGroup.rootView;
        mCompactRootView = progressGroup.compactRootView;
        mIconView = progressGroup.iconView;
        mCompactIconView = progressGroup.compactIconView;

        mIconFetcher = new IconFetcher(context);
        mMediaSessionHelper = MediaSessionManagerHelper.Companion.getInstance(context);

        mGestureDetector = mIsComposeMode ? null : new GestureDetector(mContext, new MediaGestureListener());

        updateAccentColor();
        applySystemTheming();

        if (!mIsComposeMode) {
            if (mProgressRootView != null) {
                mProgressRootView.setAlpha(0f);
                mProgressRootView.setTranslationY(ENTRY_TRANSLATION_Y);
                mProgressRootView.setVisibility(View.GONE);
            }
            
            if (mCompactRootView != null) {
                mCompactRootView.setAlpha(0f);
                mCompactRootView.setTranslationY(ENTRY_TRANSLATION_Y);
                mCompactRootView.setVisibility(View.GONE);
            }
        }

        mKeyguardStateController.addCallback(this);
        mHeadsUpManager.addListener(this);
        mNotificationListener.addNotificationHandler(this);
        mSettingsObserver.register();

        if (!mIsComposeMode) {
            if (mProgressRootView != null && mGestureDetector != null) {
                mProgressRootView.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));
            }

            if (mCompactRootView != null && mGestureDetector != null) {
                mCompactRootView.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));

                mCompactRootView.setOnClickListener(v -> {
                    onInteraction();
                });
            }
        }

        mMediaSessionHelper.addMediaMetadataListener(mMediaMetadataListener);
        
        mIsViewAttached = true;
        updateSettings();

        mHandler.postDelayed(mStaleProgressChecker, STALE_PROGRESS_CHECK_INTERVAL_MS);
    }

    /**
     * Sets a callback for Compose to receive state updates
     * @param callback Callback to be notified of state changes, or null to unregister
     */
    public void setStateCallback(StateCallback callback) {
        mStateCallback = callback;
        notifyStateCallback();
    }

    private void updateAccentColor() {
        mAccentColor = getThemeColor(mContext, android.R.attr.colorAccent);
    }

    private void applySystemTheming() {
        if (mProgressBar != null) {
            mProgressBar.setProgressTintList(ColorStateList.valueOf(mAccentColor));
        }
        if (mCircularProgressBar != null) {
            mCircularProgressBar.setProgressTintList(ColorStateList.valueOf(mAccentColor));
        }
    }

    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged called");
        
        int oldAccentColor = mAccentColor;
        updateAccentColor();
        
        Log.d(TAG, "Configuration changed - applying theming: " + 
            Integer.toHexString(oldAccentColor) + " -> " + Integer.toHexString(mAccentColor));
        
        reloadBackgrounds();
        applySystemTheming();
        mNeedsFullUiUpdate = true;
        requestUiUpdate();
    }

    private void reloadBackgrounds() {
        if (!mIsComposeMode && mProgressRootView != null) {
            mProgressRootView.setBackground(null);
            mProgressRootView.setBackgroundResource(R.drawable.action_chip_container_background);
        }
    }

    public void expandCompactView() {
        mIsExpanded = true;
        
        mHandler.removeCallbacks(mCompactCollapseRunnable);
        mHandler.postDelayed(mCompactCollapseRunnable, 5000);

        if (mIsComposeMode) {
            notifyStateCallback();
            return;
        }

        if (mCompactRootView != null && mProgressRootView != null) {
            animateViewTransition(mCompactRootView, mProgressRootView, true);
        }
        
        requestUiUpdate();
    }

    private void animateViewTransition(View fromView, View toView, boolean isExpanding) {
        if (fromView == null || toView == null) return;

        fromView.animate()
                .alpha(0f)
                .translationY(isExpanding ? -ENTRY_TRANSLATION_Y : ENTRY_TRANSLATION_Y)
                .setDuration(EXPAND_ANIMATION_DURATION)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(() -> fromView.setVisibility(View.GONE))
                .start();

        toView.setVisibility(View.VISIBLE);
        toView.setAlpha(0f);
        toView.setTranslationY(isExpanding ? ENTRY_TRANSLATION_Y : -ENTRY_TRANSLATION_Y);
        toView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(EXPAND_ANIMATION_DURATION)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .start();
    }

    private class MediaGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            onInteraction();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
                toggleMediaPlaybackState();
            }
            VibrationUtils.triggerVibration(mContext, 4);
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
                openMediaApp();
            }
            VibrationUtils.triggerVibration(mContext, 5);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (!(mShowMediaProgress && mMediaSessionHelper.isMediaPlaying())) {
                return false;
            }
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > Math.abs(e2.getY() - e1.getY()) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffX > 0) {
                    skipToNextTrack();
                } else {
                    skipToPreviousTrack();
                }
                return true;
            }
            return false;
        }
    }

    private void requestUiUpdate() {
        long currentTime = System.currentTimeMillis();
        synchronized (mLock) {
            if (mUpdatePending) {
                return;
            }
            
            long timeSinceLastUpdate = currentTime - mLastUpdateTime;
            if (timeSinceLastUpdate > DEBOUNCE_DELAY_MS) {
                mUpdatePending = false;
                mLastUpdateTime = currentTime;
                updateViews();
            } else {
                mUpdatePending = true;
                long delay = DEBOUNCE_DELAY_MS - timeSinceLastUpdate;
                mHandler.postDelayed(mUiUpdateRunnable, delay);
            }
        }
    }

    /**
     * Notifies the Compose callback of current state
     */
    private void notifyStateCallback() {
        if (mStateCallback == null) {
            return;
        }

        boolean isVisible = !mIsForceHidden && !mHeadsUpPinned && !mIsSystemChipVisible;

        boolean isMediaPlaying = mShowMediaProgress && mMediaSessionHelper.isMediaPlaying();
        boolean hasNotificationProgress = mIsEnabled && mIsTrackingProgress;

        isVisible = isVisible && (isMediaPlaying || hasNotificationProgress);

        if (isVisible) {
            float opacity = mProgressBarOpacity / 255f;
            boolean isCompact = mIsCompactModeEnabled && !mIsExpanded;
            mStateCallback.onStateChanged(
                true,
                mCurrentProgress,
                mCurrentProgressMax,
                mCurrentIcon,
                mCurrentIconIsAdaptive,
                mTrackedPackageName,
                isCompact,
                opacity,
                mIsMenuVisible,
                mChipWidth,
                mChipHeight,
                mChipPositionX,
                mChipPositionY,
                mCircularChipSize,
                mCircularPositionX,
                mCircularPositionY);
        } else {
            mStateCallback.onStateChanged(false, 0, 0, null, false, null, false, 0f, false, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private void updateViews() {
        if (!mIsViewAttached) {
            if (mIsComposeMode) {
                notifyStateCallback();
            }
            return;
        }

        float opacity = mProgressBarOpacity / 255f;

        if (mIsForceHidden || mHeadsUpPinned) {
            if (!mIsComposeMode) {
                animateExit(mProgressRootView);
                animateExit(mCompactRootView);
            }
            notifyStateCallback();
            return;
        }

        boolean isMediaPlaying = mShowMediaProgress && mMediaSessionHelper.isMediaPlaying();
        boolean shouldShowProgress = mIsEnabled && (mIsTrackingProgress || isMediaPlaying);
        
        if (!shouldShowProgress) {
            if (!mIsComposeMode) {
                animateExit(mProgressRootView);
                animateExit(mCompactRootView);
            }
            notifyStateCallback();
            return;
        }
        
        boolean shouldShowCompact = mIsCompactModeEnabled && !mIsExpanded;
        boolean shouldShowNormal = !shouldShowCompact;
        
        if (shouldShowCompact) {
            if (!mIsComposeMode && mProgressRootView != null && mProgressRootView.getVisibility() == View.VISIBLE) {
                animateExit(mProgressRootView);
            }
            
            if (!mIsComposeMode) {
                animateEntry(mCompactRootView, opacity);
            }
            
            if (isMediaPlaying) {
                updateMediaProgressCompact();
            } else {
                updateNotificationProgressCompact();
            }
        } else if (shouldShowNormal) {
            if (!mIsComposeMode && mCompactRootView != null && mCompactRootView.getVisibility() == View.VISIBLE) {
                animateExit(mCompactRootView);
            }
            
            if (isMediaPlaying) {
                if (!mIsComposeMode) {
                    animateEntry(mProgressRootView, opacity);
                }

                if (mNeedsFullUiUpdate) {
                    updateMediaProgressFull();
                    mNeedsFullUiUpdate = false;
                } else {
                    updateMediaProgressOnly();
                }
            } else {
                updateNotificationProgress(opacity);
            }
        }
        
        notifyStateCallback();
    }

    private void animateEntry(View view, float targetAlpha) {
        if (view == null) return;

        if (view.getVisibility() == View.VISIBLE && !mIsAnimatingEntry.get()) {
            return;
        }

        if (!mIsAnimatingEntry.compareAndSet(false, true)) {
            return;
        }

        cancelAnimations(view);
        
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(ENTRY_TRANSLATION_Y);
        }

        view.animate()
                .alpha(targetAlpha)
                .translationY(0f)
                .setDuration(ENTRY_ANIMATION_DURATION)
                .setInterpolator(new PathInterpolator(0f, 0f, 0.2f, 1f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mIsAnimatingEntry.set(false);
                    }
                    
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        mIsAnimatingEntry.set(false);
                    }
                })
                .start();
    }

    private void animateExit(View view) {
        if (view == null || view.getVisibility() == View.GONE) {
            return;
        }

        if (!mIsAnimatingExit.compareAndSet(false, true)) {
            return;
        }

        cancelAnimations(view);

        view.animate()
                .alpha(0f)
                .translationY(-ENTRY_TRANSLATION_Y)
                .setDuration(EXIT_ANIMATION_DURATION)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(View.GONE);
                        view.setTranslationY(ENTRY_TRANSLATION_Y);
                        mIsAnimatingExit.set(false);
                    }
                    
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        mIsAnimatingExit.set(false);
                    }
                })
                .start();
    }

    private void cancelAnimations(View view) {
        if (view == null) return;

        view.animate().cancel();
        if (view == mProgressRootView) {
            if (mProgressAnimator != null && mProgressAnimator.isRunning()) {
                mProgressAnimator.cancel();
            }
        } else if (view == mCompactRootView) {
            if (mCircularProgressAnimator != null && mCircularProgressAnimator.isRunning()) {
                mCircularProgressAnimator.cancel();
            }
        }
    }

    private void animateProgress(ProgressBar progressBar, int targetProgress) {
        if (progressBar == null) return;

        int currentProgress = progressBar.getProgress();
        
        if (Math.abs(targetProgress - currentProgress) < 2) {
            progressBar.setProgress(targetProgress);
            return;
        }

        ObjectAnimator animator = progressBar == mProgressBar ? mProgressAnimator : mCircularProgressAnimator;
        
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        animator = ObjectAnimator.ofInt(progressBar, "progress", currentProgress, targetProgress);
        animator.setDuration(PROGRESS_ANIMATION_DURATION);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.start();

        if (progressBar == mProgressBar) {
            mProgressAnimator = animator;
        } else {
            mCircularProgressAnimator = animator;
        }
    }

    private void applyLayoutParams() {
        if (mIsComposeMode) return;

        if (mProgressRootView != null) {
            ViewGroup.LayoutParams params = mProgressRootView.getLayoutParams();
            if (params != null) {
                float density = mContext.getResources().getDisplayMetrics().density;
                params.width = (int) (mChipWidth * density);
                params.height = (int) (mChipHeight * density);
                
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
                    marginParams.setMarginStart((int) (mChipPositionX * density));
                    marginParams.topMargin = (int) (mChipPositionY * density);
                }
                
                mProgressRootView.setLayoutParams(params);
            }
        }

        if (mCompactRootView != null) {
            ViewGroup.LayoutParams params = mCompactRootView.getLayoutParams();
            if (params != null) {
                float density = mContext.getResources().getDisplayMetrics().density;
                int size = (int) (mCircularChipSize * density);
                params.width = size;
                params.height = size;
                
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
                    marginParams.setMarginStart((int) (mCircularPositionX * density));
                    marginParams.topMargin = (int) (mCircularPositionY * density);
                }
                
                mCompactRootView.setLayoutParams(params);
            }
        }
    }

    private void updateMediaProgressOnly() {
        if (!mIsViewAttached && !mIsComposeMode) {
            return;
        }

        long totalDuration = mMediaSessionHelper.getTotalDuration();

        android.media.session.PlaybackState playbackState = mMediaSessionHelper.getMediaControllerPlaybackState();
        long currentProgress = 0;

        if (playbackState != null) {
            currentProgress = playbackState.getPosition();
        }

        mCurrentProgress = (int) currentProgress;
        mCurrentProgressMax = (int) totalDuration;
        if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

        if (!mIsComposeMode && mProgressRootView != null && 
            mProgressRootView.getVisibility() == View.VISIBLE && mProgressBar != null && totalDuration > 0) {
            mProgressBar.setMax((int) totalDuration);
            animateProgress(mProgressBar, (int) currentProgress);
        }

        if (!mIsComposeMode && mCompactRootView != null && 
            mCompactRootView.getVisibility() == View.VISIBLE && mCircularProgressBar != null && totalDuration > 0) {
            mCircularProgressBar.setMax((int) totalDuration);
            animateProgress(mCircularProgressBar, (int) currentProgress);
        }

        if (mIsComposeMode) {
            notifyStateCallback();
        }
    }

    @Nullable
    private String getMediaPackageName() {
        android.media.session.PlaybackState playbackState = mMediaSessionHelper.getMediaControllerPlaybackState();
        if (playbackState != null && playbackState.getExtras() != null) {
            return playbackState.getExtras().getString("package");
        }
        return null;
    }

    private void updateMediaProgressFull() {
        if (!mIsViewAttached && !mIsComposeMode) return;

        if (!mIsComposeMode && mProgressRootView != null) {
            mProgressRootView.setVisibility(View.VISIBLE);
        }

        mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
        mMediaProgressHandler.post(mMediaProgressRunnable);

        Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();

        if (mediaAppIcon != null) {
            mCurrentIcon = mediaAppIcon;
            mCurrentIconIsAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
            if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(mediaAppIcon);
            mLastLoadedIconPackage = null;
        } else {
            String packageName = getMediaPackageName();
            
            if (packageName != null && !packageName.equals(mLastLoadedIconPackage)) {
                mLastLoadedIconPackage = packageName;
                loadIconInBackground(packageName, result -> {
                    Drawable drawable = result != null ? result.drawable : null;
                    boolean isAdaptive = result != null ? result.isAdaptive : false;

                    if (drawable != null) {
                        mCurrentIcon = drawable;
                        mCurrentIconIsAdaptive = isAdaptive;
                        if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(drawable);
                    } else {
                        setDefaultMediaIcon();
                    }
                    if (mIsComposeMode) notifyStateCallback();
                });
            } else if (packageName == null) {
                setDefaultMediaIcon();
                mLastLoadedIconPackage = null;
            }
        }

        updateMediaProgressOnly();
    }

    private void setDefaultMediaIcon() {
        mCurrentIcon = mContext.getResources().getDrawable(R.drawable.ic_default_music_icon);
        mCurrentIconIsAdaptive = false;
        if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(mCurrentIcon);
    }

    private void updateMediaProgressCompact() {
        if (!mIsViewAttached && !mIsComposeMode) return;

        if (!mIsComposeMode && mCompactRootView != null) {
            mCompactRootView.setVisibility(View.VISIBLE);
        }

        mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
        mMediaProgressHandler.post(mMediaProgressRunnable);

        long totalDuration = mMediaSessionHelper.getTotalDuration();

        android.media.session.PlaybackState playbackState = mMediaSessionHelper.getMediaControllerPlaybackState();
        long currentProgress = 0;

        if (playbackState != null) {
            currentProgress = playbackState.getPosition();
        }

        mCurrentProgress = (int) currentProgress;
        mCurrentProgressMax = (int) totalDuration;
        if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

        if (!mIsComposeMode && totalDuration > 0 && mCircularProgressBar != null) {
            mCircularProgressBar.setMax((int) totalDuration);
            animateProgress(mCircularProgressBar, (int) currentProgress);
        }

        Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();
        
        if (mediaAppIcon != null) {
            mCurrentIcon = mediaAppIcon;
            mCurrentIconIsAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
            if (!mIsComposeMode && mCompactIconView != null) {
                mCompactIconView.setImageDrawable(mediaAppIcon);
            }
            mLastLoadedCompactIconPackage = null;
        } else {
            String packageName = getMediaPackageName();
            
            if (packageName != null && !packageName.equals(mLastLoadedCompactIconPackage)) {
                mLastLoadedCompactIconPackage = packageName;
                loadIconInBackground(packageName, result -> {
                    Drawable drawable = result != null ? result.drawable : null;
                    boolean isAdaptive = result != null ? result.isAdaptive : false;

                    if (drawable != null) {
                        mCurrentIcon = drawable;
                        mCurrentIconIsAdaptive = isAdaptive;
                        if (!mIsComposeMode && mCompactIconView != null) mCompactIconView.setImageDrawable(drawable);
                    } else {
                        setDefaultMediaIconCompact();
                    }
                    if (mIsComposeMode) notifyStateCallback();
                });
            } else if (packageName == null) {
                setDefaultMediaIconCompact();
                mLastLoadedCompactIconPackage = null;
            }
        }
    }

    private void setDefaultMediaIconCompact() {
        mCurrentIcon = mContext.getResources().getDrawable(R.drawable.ic_default_music_icon);
        mCurrentIconIsAdaptive = false;
        if (!mIsComposeMode && mCompactIconView != null) mCompactIconView.setImageDrawable(mCurrentIcon);
    }

    private void updateNotificationProgress(float opacity) {
        if (!mIsViewAttached && !mIsComposeMode) return;
        
        if (!mIsEnabled || !mIsTrackingProgress) {
            if (!mIsComposeMode) {
                animateExit(mProgressRootView);
            }
            mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
            return;
        }

        if (!mIsComposeMode) {
            animateEntry(mProgressRootView, opacity);
        }
        
        if (mCurrentProgressMax <= 0) {
            Log.w(TAG, "updateViews: invalid max progress " + mCurrentProgressMax + ", using 100");
            mCurrentProgressMax = 100;
        }

        if (!mIsComposeMode && mProgressBar != null) {
            mProgressBar.setMax(mCurrentProgressMax);
            animateProgress(mProgressBar, mCurrentProgress);
        }

        if (mTrackedPackageName != null && !mTrackedPackageName.equals(mLastLoadedIconPackage)) {
            mLastLoadedIconPackage = mTrackedPackageName;
            loadIconInBackground(mTrackedPackageName, result -> {
                Drawable drawable = result != null ? result.drawable : null;
                boolean isAdaptive = result != null ? result.isAdaptive : false;

                mCurrentIcon = drawable;
                mCurrentIconIsAdaptive = isAdaptive;
                if (!mIsComposeMode && mIconView != null && drawable != null) {
                    mIconView.setImageDrawable(drawable);
                }
                if (mIsComposeMode) notifyStateCallback();
            });
        }
    }
    
    private void updateNotificationProgressCompact() {
        if (!mIsViewAttached && !mIsComposeMode) return;
        
        if (!mIsEnabled || !mIsTrackingProgress) {
            if (!mIsComposeMode && mCompactRootView != null) {
                mCompactRootView.setVisibility(View.GONE);
            }
            mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
            return;
        }

        if (!mIsComposeMode && mCompactRootView != null) {
            mCompactRootView.setVisibility(View.VISIBLE);
        }
        if (mCurrentProgressMax <= 0) {
            Log.w(TAG, "updateViews: invalid max progress " + mCurrentProgressMax + ", using 100");
            mCurrentProgressMax = 100;
        }

        if (!mIsComposeMode && mCircularProgressBar != null) {
            mCircularProgressBar.setMax(mCurrentProgressMax);
            animateProgress(mCircularProgressBar, mCurrentProgress);
        }

        if (mTrackedPackageName != null && !mTrackedPackageName.equals(mLastLoadedCompactIconPackage)) {
            mLastLoadedCompactIconPackage = mTrackedPackageName;
            loadIconInBackground(mTrackedPackageName, result -> {
                Drawable drawable = result != null ? result.drawable : null;
                boolean isAdaptive = result != null ? result.isAdaptive : false;

                mCurrentIcon = drawable;
                mCurrentIconIsAdaptive = isAdaptive;
                if (!mIsComposeMode && mCompactIconView != null && drawable != null) {
                    mCompactIconView.setImageDrawable(drawable);
                }
                if (mIsComposeMode) notifyStateCallback();
            });
        }
    }

    private void loadIconInBackground(String packageName, IconCallback callback) {
        if (packageName == null) return;

        synchronized (mLock) {
            if (mIconCache.containsKey(packageName)) {
                IconFetcher.AdaptiveDrawableResult cachedResult = mIconCache.get(packageName);
                if (cachedResult != null) {
                    callback.onIconLoaded(cachedResult);
                    return;
                }
            }
        }
        
        mBackgroundExecutor.execute(() -> {
            if (!mIsViewAttached) {
                return;
            }
            
            try {
                final IconFetcher.AdaptiveDrawableResult iconResult = 
                        mIconFetcher.getMonotonicPackageIcon(packageName);
                
                if (iconResult != null && iconResult.drawable != null) {
                    if (mIsComposeMode) {
                        int sizePx = (int) (24 * mContext.getResources().getDisplayMetrics().density);
                        iconResult.drawable.setBounds(0, 0, sizePx, sizePx);
                    }

                    synchronized (mLock) {
                        if (mIconCache.size() >= MAX_ICON_CACHE_SIZE) {
                            mIconCache.clear();
                        }
                        mIconCache.put(packageName, iconResult);
                    }
                    
                    mHandler.post(() -> {
                        if (mIsViewAttached) {
                            callback.onIconLoaded(iconResult);
                        }
                    });
                } else {
                    Log.w(TAG, "Failed to load icon for package: " + packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading icon for package: " + packageName, e);
            }
        });
    }
    
    private interface IconCallback {
        void onIconLoaded(@Nullable IconFetcher.AdaptiveDrawableResult result);
    }

    private void extractProgress(Notification notification) {
        Bundle extras = notification.extras;
        mCurrentProgressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100);
        mCurrentProgress = extras.getInt(Notification.EXTRA_PROGRESS, 0);
    }

    private void trackProgress(final StatusBarNotification sbn) {
        mIsTrackingProgress = true;
        mTrackedNotificationKey = sbn.getKey();
        mTrackedPackageName = sbn.getPackageName();
        mLastProgressUpdateTime = System.currentTimeMillis();
        extractProgress(sbn.getNotification());
        requestUiUpdate();
    }

    private void clearProgressTracking() {
        mIsTrackingProgress = false;
        mTrackedNotificationKey = null;
        mTrackedPackageName = null;
        mCurrentProgress = 0;
        mCurrentProgressMax = 0;
        mLastProgressUpdateTime = 0;
        mLastLoadedIconPackage = null;
        mLastLoadedCompactIconPackage = null;
        requestUiUpdate();
    }

    private void checkForStaleProgress() {
        if (!mIsTrackingProgress || mTrackedNotificationKey == null) return;

        StatusBarNotification sbn = findNotificationByKey(mTrackedNotificationKey);
        if (sbn == null) {
            clearProgressTracking();
            return;
        }

        if (!hasProgress(sbn.getNotification())) {
            clearProgressTracking();
            return;
        }

        if (mLastProgressUpdateTime > 0 &&
            System.currentTimeMillis() - mLastProgressUpdateTime > PROGRESS_TIMEOUT_MS &&
            mCurrentProgressMax > 0 &&
            mCurrentProgress >= mCurrentProgressMax) {
            clearProgressTracking();
        }
    }

    private void updateProgressIfNeeded(final StatusBarNotification sbn) {
        if (!mIsTrackingProgress) return;

        if (sbn.getKey().equals(mTrackedNotificationKey)) {
            if (!hasProgress(sbn.getNotification())) {
                clearProgressTracking();
                return;
            }

            mLastProgressUpdateTime = System.currentTimeMillis();
            extractProgress(sbn.getNotification());
            requestUiUpdate();
        }
    }

    @Nullable
    private StatusBarNotification findNotificationByKey(String key) {
        if (key == null || mNotificationListener == null) return null;
        
        for (StatusBarNotification notification : mNotificationListener.getActiveNotifications()) {
            if (notification.getKey().equals(key)) {
                return notification;
            }
        }
        return null;
    }

    private static boolean hasProgress(@NonNull final Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return false;
        
        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        boolean maxProgressValid = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0;
        return extras.containsKey(Notification.EXTRA_PROGRESS) &&
               extras.containsKey(Notification.EXTRA_PROGRESS_MAX) &&
               !indeterminate && maxProgressValid;
    }

    public void onInteraction() {
        if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
            if (mIsComposeMode) {
                mIsMenuVisible = !mIsMenuVisible;
                notifyStateCallback();
                if (mIsMenuVisible) {
                    mHandler.removeCallbacks(mMenuCollapseRunnable);
                    mHandler.postDelayed(mMenuCollapseRunnable, 5000);
                }
            } else {
                showMediaPopup(mProgressRootView);
            }
        } else {
            openTrackedApp();
        }
        VibrationUtils.triggerVibration(mContext, 3);
    }

    public void onLongPress() {
        if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
            openMediaApp();
        } else {
            openTrackedApp();
        }
        VibrationUtils.triggerVibration(mContext, 5);
    }

    public void onDoubleTap() {
        if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
            toggleMediaPlaybackState();
            VibrationUtils.triggerVibration(mContext, 4);
        }
    }

    public void onSwipe(boolean isNext) {
        if (isNext) skipToNextTrack();
        else skipToPreviousTrack();
    }

    public void onMediaAction(int action) {
        if (action == 0) skipToPreviousTrack();
        else if (action == 1) toggleMediaPlaybackState();
        else if (action == 2) skipToNextTrack();
        mHandler.removeCallbacks(mMenuCollapseRunnable);
        mHandler.postDelayed(mMenuCollapseRunnable, 5000);
    }

    public void onMediaMenuDismiss() {
        mIsMenuVisible = false;
        notifyStateCallback();
    }

    public void setSystemChipVisible(boolean visible) {
        if (mIsSystemChipVisible != visible) {
            mIsSystemChipVisible = visible;
            notifyStateCallback();
            requestUiUpdate();
        }
    }

    private void showMediaPopup(View anchorView) {
        if (mIsComposeMode || anchorView == null) {
            return;
        }

        if (mIsPopupActive) {
            if (mMediaPopup != null) {
                mMediaPopup.dismiss();
            }
            mIsPopupActive = false;
            return;
        }

        Context context = anchorView.getContext();
        View popupView = LayoutInflater.from(context).inflate(R.layout.media_control_popup, null);
        
        if (mMediaPopup != null && mMediaPopup.isShowing()) {
            mMediaPopup.dismiss();
        }
        
        mMediaPopup = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mMediaPopup.setOutsideTouchable(true);
        mMediaPopup.setFocusable(true);
        mMediaPopup.setOnDismissListener(() -> mIsPopupActive = false);

        ImageButton btnPrevious = popupView.findViewById(R.id.btn_previous);
        ImageButton btnNext = popupView.findViewById(R.id.btn_next);
        
        if (btnPrevious != null) {
            btnPrevious.setOnClickListener(v -> {
                skipToPreviousTrack();
                mMediaPopup.dismiss();
            });
        }
        
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                skipToNextTrack();
                mMediaPopup.dismiss();
            });
        }

        anchorView.post(() -> {
            if (!mIsViewAttached) return;
            
            int offsetX = -popupView.getWidth() / 3;
            int offsetY = -anchorView.getHeight();
            mMediaPopup.showAsDropDown(anchorView, offsetX, offsetY);
            mIsPopupActive = true;
        });
    }

    private void openTrackedApp() {
        if (mTrackedPackageName == null) {
            Log.w(TAG, "No tracked package available");
            return;
        }

        Intent launchIntent = mContext.getPackageManager().getLaunchIntentForPackage(mTrackedPackageName);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(launchIntent);
        } else {
            Log.w(TAG, "No launch intent for package: " + mTrackedPackageName);
        }
    }

    private void onNotificationPosted(final StatusBarNotification sbn) {
        if (sbn == null || !mIsEnabled) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        synchronized (this) {
            boolean hasValidProgress = hasProgress(notification);
            String currentKey = mTrackedNotificationKey;

            if (!hasValidProgress) {
                if (currentKey != null && currentKey.equals(sbn.getKey())) {
                    clearProgressTracking();
                }
                return;
            }

            if (!mIsTrackingProgress) {
                trackProgress(sbn);
            } else if (sbn.getKey().equals(currentKey)) {
                updateProgressIfNeeded(sbn);
            }
        }
    }

    private void onNotificationRemoved(final StatusBarNotification sbn) {
        if (sbn == null) return;

        synchronized (this) {
            if (!mIsTrackingProgress) return;

            if (sbn.getKey().equals(mTrackedNotificationKey)) {
                clearProgressTracking();
                return;
            }

            if (sbn.getPackageName().equals(mTrackedPackageName)) {
                StatusBarNotification currentSbn = findNotificationByKey(mTrackedNotificationKey);
                if (currentSbn == null || !hasProgress(currentSbn.getNotification())) {
                    clearProgressTracking();
                }
            }
        }
    }

    public void setForceHidden(final boolean forceHidden) {
        if (mIsForceHidden != forceHidden) {
            Log.d(TAG, "setForceHidden " + forceHidden);
            mIsForceHidden = forceHidden;
            notifyStateCallback();
            requestUiUpdate();
        }
    }

    private void toggleMediaPlaybackState() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.toggleMediaPlaybackState(); 
        }
    }
    
    private void skipToNextTrack() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.nextSong(); 
        }
    }
    
    private void skipToPreviousTrack() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.prevSong(); 
        }
    }
    
    private void openMediaApp() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.launchMediaApp(); 
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap) {
        onNotificationPosted(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap) {
        onNotificationRemoved(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap, int _reason) {
        onNotificationRemoved(sbn);
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
        mHeadsUpPinned = inPinnedMode;
        notifyStateCallback();
        requestUiUpdate();
    }

    @Override
    public void onNotificationRankingUpdate(NotificationListenerService.RankingMap _rankingMap) {
    }
    
    @Override
    public void onNotificationsInitialized() {
    }

    @Override
    public void onKeyguardShowingChanged() {
        setForceHidden(mKeyguardStateController.isShowing());
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) { super(handler); }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED)) ||
                    uri.equals(Settings.System.getUriFor(SHOW_MEDIA_PROGRESS)) ||
                    uri.equals(Settings.System.getUriFor(PROGRESS_BAR_OPACITY)) ||
                    uri.equals(Settings.System.getUriFor(COMPACT_MODE_ENABLED)) ||
                    uri.equals(Settings.System.getUriFor(CHIP_POSITION_X)) ||
                    uri.equals(Settings.System.getUriFor(CHIP_POSITION_Y)) ||
                    uri.equals(Settings.System.getUriFor(CHIP_WIDTH)) ||
                    uri.equals(Settings.System.getUriFor(CHIP_HEIGHT)) ||
                    uri.equals(Settings.System.getUriFor(CIRCULAR_CHIP_SIZE)) ||
                    uri.equals(Settings.System.getUriFor(CIRCULAR_POSITION_X)) ||
                    uri.equals(Settings.System.getUriFor(CIRCULAR_POSITION_Y))) {
                updateSettings();
            }
        }

        public void register() {
            mContentResolver.registerContentObserver(Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(SHOW_MEDIA_PROGRESS), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(PROGRESS_BAR_OPACITY), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(COMPACT_MODE_ENABLED), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(CHIP_POSITION_X), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(CHIP_POSITION_Y), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(CHIP_WIDTH), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(CHIP_HEIGHT), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(CIRCULAR_CHIP_SIZE), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(CIRCULAR_POSITION_X), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(CIRCULAR_POSITION_Y), 
                    false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        public void unregister() { 
            mContentResolver.unregisterContentObserver(this); 
        }
    }

    private void updateSettings() {
        boolean wasEnabled = mIsEnabled;
        boolean wasShowingMedia = mShowMediaProgress;
        boolean wasCompactMode = mIsCompactModeEnabled;
        
        mIsEnabled = Settings.System.getIntForUser(mContentResolver, 
                ONGOING_ACTION_CHIP_ENABLED, 1, UserHandle.USER_CURRENT) == 1;
        mShowMediaProgress = Settings.System.getIntForUser(mContentResolver, 
                SHOW_MEDIA_PROGRESS, 0, UserHandle.USER_CURRENT) == 1;
        mIsCompactModeEnabled = Settings.System.getIntForUser(mContentResolver, 
                COMPACT_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

        if (wasEnabled && !mIsEnabled) {
            clearProgressTracking();
            if (!mIsComposeMode) {
                animateExit(mProgressRootView);
                animateExit(mCompactRootView);
            }
            mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
            mHandler.removeCallbacks(mStaleProgressChecker);
        }
        
        int opacityPercentage = Settings.System.getIntForUser(mContentResolver, 
                PROGRESS_BAR_OPACITY, DEFAULT_OPACITY_PERCENTAGE, UserHandle.USER_CURRENT);
        
        opacityPercentage = Math.max(0, Math.min(100, opacityPercentage));
        mProgressBarOpacity = (int)(opacityPercentage * 2.55f);
        
        mChipWidth = Settings.System.getIntForUser(
            mContentResolver, CHIP_WIDTH, DEFAULT_CHIP_WIDTH, UserHandle.USER_CURRENT);
        mChipWidth = Math.max(MIN_CHIP_WIDTH, Math.min(MAX_CHIP_WIDTH, mChipWidth));
        
        mChipHeight = Settings.System.getIntForUser(
            mContentResolver, CHIP_HEIGHT, DEFAULT_CHIP_HEIGHT, UserHandle.USER_CURRENT);
        mChipHeight = Math.max(MIN_CHIP_HEIGHT, Math.min(MAX_CHIP_HEIGHT, mChipHeight));
        
        mChipPositionX = Settings.System.getIntForUser(
            mContentResolver, CHIP_POSITION_X, DEFAULT_POSITION_X, UserHandle.USER_CURRENT);
        
        mChipPositionY = Settings.System.getIntForUser(
            mContentResolver, CHIP_POSITION_Y, DEFAULT_POSITION_Y, UserHandle.USER_CURRENT);
        
        int newCircularSize = Settings.System.getIntForUser(mContentResolver, 
                CIRCULAR_CHIP_SIZE, DEFAULT_CIRCULAR_SIZE, UserHandle.USER_CURRENT);
        mCircularChipSize = Math.max(MIN_CIRCULAR_SIZE, Math.min(MAX_CIRCULAR_SIZE, newCircularSize));
        
        mCircularPositionX = Settings.System.getIntForUser(mContentResolver, 
                CIRCULAR_POSITION_X, DEFAULT_POSITION_X, UserHandle.USER_CURRENT);
        mCircularPositionY = Settings.System.getIntForUser(mContentResolver, 
                CIRCULAR_POSITION_Y, DEFAULT_POSITION_Y, UserHandle.USER_CURRENT);
        
        if (wasEnabled != mIsEnabled || wasShowingMedia != mShowMediaProgress || 
                wasCompactMode != mIsCompactModeEnabled) {
            mNeedsFullUiUpdate = true;
            mIsExpanded = false;
        }
        
        applyLayoutParams();
        requestUiUpdate();
        updateAccentColor();
        applySystemTheming();
    }

    public void destroy() {
        mIsViewAttached = false;

        mHandler.removeCallbacks(mStaleProgressChecker);
        mHandler.removeCallbacks(mUiUpdateRunnable);
        mHandler.removeCallbacks(mCompactCollapseRunnable);
        mHandler.removeCallbacks(mMenuCollapseRunnable);
        mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
        mHandler.removeCallbacksAndMessages(null);

        // Cancel all animations
        if (!mIsComposeMode) {
            cancelAnimations(mProgressRootView);
            cancelAnimations(mCompactRootView);
        }
        
        if (mProgressAnimator != null) {
            mProgressAnimator.cancel();
            mProgressAnimator = null;
        }
        
        if (mCircularProgressAnimator != null) {
            mCircularProgressAnimator.cancel();
            mCircularProgressAnimator = null;
        }

        mSettingsObserver.unregister();
        mKeyguardStateController.removeCallback(this);
        mHeadsUpManager.removeListener(this);
        mNotificationListener.removeNotificationHandler(this);
        mMediaSessionHelper.removeMediaMetadataListener(mMediaMetadataListener);
        
        if (mMediaPopup != null && mMediaPopup.isShowing()) {
            mMediaPopup.dismiss();
        }
        mMediaPopup = null;
        
        synchronized (mLock) {
            mIsTrackingProgress = false;
            mTrackedNotificationKey = null;
            mTrackedPackageName = null;
            mLastLoadedIconPackage = null;
            mLastLoadedCompactIconPackage = null;
            mIconCache.clear();
        }
        
        if (!mIsComposeMode && mIconView != null) {
            mIconView.setImageDrawable(null);
        }
        
        if (!mIsComposeMode && mCompactIconView != null) {
            mCompactIconView.setImageDrawable(null);
        }
        mCurrentIcon = null;

        if (mBackgroundExecutor != null) {
            mBackgroundExecutor.shutdown();
            try {
                if (!mBackgroundExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Executor did not terminate in time, forcing shutdown");
                    mBackgroundExecutor.shutdownNow();
                    
                    if (!mBackgroundExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        Log.e(TAG, "Executor did not terminate after shutdownNow");
                    }
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for executor shutdown", e);
                mBackgroundExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int getThemeColor(Context context, int attrResId) {
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(attrResId, typedValue, true);
        return typedValue.data;
    }
}
