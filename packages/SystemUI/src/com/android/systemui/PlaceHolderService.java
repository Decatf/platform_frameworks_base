/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.util.Slog;
import com.android.systemui.R;
import android.view.Display;

public class PlaceHolderService extends Service {
    static final String TAG = "PlaceHolderService";
    private WindowManager windowManager;
    private ImageView invisibleImg;

    @Override public IBinder onBind(Intent intent) {
        // Not used
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        invisibleImg = new ImageView(this);
        // invisibleImg.setImageResource(R.drawable.android);
        invisibleImg.setImageResource(R.drawable.dot);
        invisibleImg.setImageAlpha(0);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // WindowManager.LayoutParams.TYPE_PHONE,
            // WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
            // WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
            WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
            // WindowManager.LayoutParams.TYPE_PRIORITY_PHONE,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        params.setTitle("invisible layer");

        Display display = windowManager.getDefaultDisplay();
        int width = display.getWidth();
        int height = display.getHeight();

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = width + 24;
        params.y = height + 24;
        // params.x = 0;
        // params.y = 0;

        windowManager.addView(invisibleImg, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (invisibleImg != null)
            windowManager.removeView(invisibleImg);
    }
}
