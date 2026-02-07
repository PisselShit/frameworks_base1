/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021-2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.lunaris;

import android.app.ActivityThread;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Environment;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.lunaris.KeyProviderManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public final class PixelPropsUtils {

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String DATA_FILE = "gms_certified_props.json";
    
    private static final String PACKAGE_VENDING = "com.android.vending";

    private static final Map<String, Object> propsToChangeGeneric = new HashMap<>();
    private static final Map<String, Object> propsToChangePixel10ProXL = new HashMap<>();
    private static final Map<String, Object> propsToChangePixelTablet = new HashMap<>();
    private static final Map<String, Object> propsToChangePixelXL = new HashMap<>();

    private static final ArraySet<String> PKGS_RECENT_PIXEL = new ArraySet<>(); // Pixel device

    static {
        Collections.addAll(PKGS_RECENT_PIXEL,
            "com.google.android.aicore",
            "com.google.android.apps.aiwallpapers",
            "com.google.android.apps.bard",
            "com.google.android.apps.customization.pixel",
            "com.google.android.apps.emojiwallpaper",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.photos",
            "com.google.android.apps.pixel.agent",
            "com.google.android.apps.pixel.creativeassistant",
            "com.google.android.apps.pixel.nowplaying",
            "com.google.android.apps.pixel.psi",
            "com.google.android.apps.pixel.subzero",
            "com.google.android.apps.pixel.support",
            "com.google.android.apps.privacy.wildlife",
            "com.google.android.apps.wallpaper",
            "com.google.android.apps.wallpaper.pixel",
            "com.google.android.apps.weather",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.google.android.pcs",
            "com.google.android.settings.intelligence",
            "com.google.android.wallpaper.effects",
            "com.google.pixel.livewallpaper",
            "com.netflix.mediaclient",
            "com.nhs.online.nhsonline"
        );

        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
        propsToChangePixel10ProXL.put("BRAND", "google");
        propsToChangePixel10ProXL.put("MANUFACTURER", "Google");
        propsToChangePixel10ProXL.put("DEVICE", "mustang");
        propsToChangePixel10ProXL.put("PRODUCT", "mustang");
        propsToChangePixel10ProXL.put("HARDWARE", "mustang");
        propsToChangePixel10ProXL.put("MODEL", "Pixel 10 Pro XL");
        propsToChangePixel10ProXL.put("ID", "BP4A.260205.001");
        propsToChangePixel10ProXL.put("FINGERPRINT", "google/mustang/mustang:16/BP4A.260205.001/14624707:user/release-keys");
        propsToChangePixelTablet.put("BRAND", "google");
        propsToChangePixelTablet.put("MANUFACTURER", "Google");
        propsToChangePixelTablet.put("DEVICE", "tangorpro");
        propsToChangePixelTablet.put("PRODUCT", "tangorpro");
        propsToChangePixelTablet.put("HARDWARE", "tangorpro");
        propsToChangePixelTablet.put("MODEL", "Pixel Tablet");
        propsToChangePixelTablet.put("ID", "BP4A.260105.004.E1");
        propsToChangePixelTablet.put("FINGERPRINT", "google/tangorpro/tangorpro:16/BP4A.260105.004.E1/14587043:user/release-keys");
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("HARDWARE", "marlin");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("ID", "QP1A.191005.007.A3");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
    }

    private static volatile List<String> sCertifiedProps;
    private static volatile long sCertPropsMtime = -1;

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        if (packageName == null || packageName.isEmpty()) {
            if (DEBUG) Log.d(TAG, "Null received in setProps.");
            return;
        }

        if (android.os.Process.isIsolated()) {
            if (DEBUG) Log.d(TAG, "Skipping setProps in isolated process");
            return;
        }

        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        if (packageName.equals(PACKAGE_VENDING)) {
            if (Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.PI_ENABLE_SPOOF, 1) == 1) {
                if (DEBUG) Log.d(TAG, "Spoofing Play Store with certified props");
                spoofBuildGms(context);
            } else {
                if (DEBUG) Log.d(TAG, "Play Store spoofing disabled by setting");
            }
            return;
        }

        if (PKGS_RECENT_PIXEL.contains(packageName)) {
            Map<String,Object> propsToChange = null;

            if (packageName.equals("com.google.android.apps.photos")) {
                if (Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.PI_PHOTOS_SPOOF, 1) == 1) {
                    if (DEBUG) Log.d(TAG, "Gphotos spoofing disabled by setting");
                    propsToChange = propsToChangePixelXL;
                }
            } else if (packageName.equals("com.netflix.mediaclient") &&
                        Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.PI_NETFLIX_SPOOF, 0) != 1) {
                    if (DEBUG) Log.d(TAG, "Netflix spoofing disabled by setting");
                    return;
            } else if (packageName.equals("com.google.android.gms")) {
                final String processName = Application.getProcessName().toLowerCase();
                if (processName.contains("unstable")) {
                    spoofBuildGms(context);
                    return;
                }
                return;
            } else if (packageName.equals("com.google.android.settings.intelligence")) {
                setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
                return;
            } else {
                if (isDeviceTablet(context.getApplicationContext())) {
                    propsToChange = propsToChangePixelTablet;
                } else {
                    propsToChange = propsToChangePixel10ProXL;
                }
            }

            if (propsToChange != null) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                applyProps(propsToChange);
            }
        }

        // Apply game props if enabled
        setGameProps(context, packageName);
    }

    private static void applyProps(Map<String,Object> props) {
      for (Map.Entry<String,Object> e : props.entrySet())
        setPropValue(e.getKey(), e.getValue());
    }

    private static boolean isDeviceTablet(Context context) {
        if (context == null) {
            if (DEBUG) Log.d(TAG, "Null received in isDeviceTablet.");
            return false;
        }
        Configuration config = context.getResources().getConfiguration();
        boolean isTablet = (config.smallestScreenWidthDp >= 600);
        return isTablet;
    }

    private static void setPropValue(String key, Object value) {
        setPropValue(key, String.valueOf(value));
    }

    private static void setPropValue(String key, String value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value);
            Class<?> clazz = Build.class;
            if (key.startsWith("VERSION.")) {
                clazz = Build.VERSION.class;
                key = key.substring(8);
            }
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            // Determine the field type and parse the value accordingly.
            if (field.getType().equals(Integer.TYPE)) {
                field.set(null, Integer.parseInt(value));
            } else if (field.getType().equals(Long.TYPE)) {
                field.set(null, Long.parseLong(value));
            } else {
                field.set(null, value);
            }
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static Map<String, String> getGameProps(String packageName) {
        Map<String, String> gamePropsToChange = new HashMap<>();
        String[] keys = {"BRAND", "DEVICE", "MANUFACTURER", "MODEL", "FINGERPRINT", "PRODUCT"};
        for (String key : keys) {
            String systemPropertyKey = "persist.sys.gameprops." + packageName + "." + key;
            String value = SystemProperties.get(systemPropertyKey);
            if (value != null && !value.isEmpty()) {
                gamePropsToChange.put(key, value);
                if (DEBUG) Log.d(TAG, "Got system property: " + systemPropertyKey + " = " + value);
            }
        }
        return gamePropsToChange;
    }

    public static void setGameProps(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        
        try {
            if (Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.PI_GAMES_SPOOF, 0) != 1) {
                return;
            }
        } catch (Exception e) {
            if (DEBUG) Log.d(TAG, "Skipping game props - Settings not available yet");
            return;
        }
        
        Map<String, String> gamePropsToChange = getGameProps(packageName);
        if (!gamePropsToChange.isEmpty()) {
            if (DEBUG) Log.d(TAG, "Defining game props for: " + packageName);
            for (Map.Entry<String, String> prop : gamePropsToChange.entrySet()) {
                String key = prop.getKey();
                String value = prop.getValue();
                if (DEBUG) Log.d(TAG, "Defining game prop " + key + " for: " + packageName);
                setPropValue(key, value);
            }
        }
    }

    private static void spoofBuildGms(Context context) {
        if (Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.PI_ENABLE_SPOOF, 1) != 1) {
            if (DEBUG) Log.d(TAG, "GMS spoofing disabled by setting");
            return;
        }

        File dataFile = new File(Environment.getDataSystemDirectory(), DATA_FILE);
        long mtime = dataFile.exists() ? dataFile.lastModified() : -1;

        if (mtime == sCertPropsMtime && sCertifiedProps != null && !sCertifiedProps.isEmpty()) {
            if (DEBUG) Log.d(TAG, "New certification props not found, applying existing ones");
            applyCertifiedProps();
            return;
        }

        String savedProps = readFromFile(dataFile);
        List<String> fresh = new ArrayList<>();
        if (TextUtils.isEmpty(savedProps)) {
            if (DEBUG) Log.d(TAG, "Certification props not available! Not applied.");
            return;
        }
        if (DEBUG) Log.d(TAG, "Parsing props fetched by attestation service");
        try {
            JSONObject parsedProps = new JSONObject(savedProps);
            Iterator<String> keys = parsedProps.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = parsedProps.getString(key);
                fresh.add(key + ":" + value);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON data", e);
            return;
        }
        sCertifiedProps = new ArrayList<>(fresh);
        sCertPropsMtime = mtime;
        if (sCertifiedProps != null && !sCertifiedProps.isEmpty()) {
            if (DEBUG) Log.d(TAG, "New certification props found, applying new ones");
            applyCertifiedProps();
        }
    }

    private static void applyCertifiedProps() {
        for (String entry : sCertifiedProps) {
            String[] kv = entry.split(":", 2);
            if (kv.length == 2) setPropValue(kv[0], kv[1]);
        }
    }

    private static String readFromFile(File file) {
        StringBuilder content = new StringBuilder();

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from file", e);
            }
        }
        return content.toString();
    }

    private static boolean isCallerSafetyNet() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            final String cn = e.getClassName();
            if (cn != null && (cn.contains("DroidGuard") || cn.contains("droidguard"))) return true;
        }
        return false;
    }

    public static void onEngineGetCertificateChain() {
        if (android.os.Process.isIsolated()) {
            if (DEBUG) Log.d(TAG, "Skipping onEngineGetCertificateChain in isolated process");
            return;
        }

        Context context = ActivityThread.currentApplication() != null
                ? ActivityThread.currentApplication().getApplicationContext()
                : null;
        if (context == null) {
            if (DEBUG) Log.d(TAG, "Null received in onEngineGetCertificateChain.");
            return;
        }

        boolean isKeyBoxAvailable = KeyProviderManager.isKeyboxAvailable();

        if (!isKeyBoxAvailable && Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.PI_ENABLE_SPOOF, 1) != 1) {
            if (DEBUG) Log.d(TAG, "onEngineGetCertificateChain disabled by setting");
            return;
        }

        // If a keybox is found, don't block key attestation
        if (isKeyBoxAvailable && Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.PI_GMS_CERT_CHAIN, 0) == 1) {
            Log.i(TAG, "Key attestation blocking is disabled because a keybox is defined to spoof");
            return;
        }

        // Check stack for SafetyNet
        if (isCallerSafetyNet()) {
            Log.i(TAG, "Blocked key attestation");
            throw new UnsupportedOperationException();
        }
    }
}
