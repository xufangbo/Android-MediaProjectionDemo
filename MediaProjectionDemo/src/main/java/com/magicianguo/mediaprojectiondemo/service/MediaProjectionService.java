package com.magicianguo.mediaprojectiondemo.service;

import static android.hardware.display.VirtualDisplay.*;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.magicianguo.mediaprojectiondemo.App;
import com.magicianguo.mediaprojectiondemo.constant.ServiceType;
import com.magicianguo.mediaprojectiondemo.util.MediaProjectionHelper;
import com.magicianguo.mediaprojectiondemo.util.NotificationHelper;
import com.magicianguo.mediaprojectiondemo.util.ToastUtils;
import com.magicianguo.mediaprojectiondemo.util.WindowHelper;
import com.magicianguo.mediaprojectiondemo.view.ProjectionView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MediaProjectionService extends Service {
    private static final String TAG = "MediaProjectionService";
    @Nullable
    private static MediaProjection mMediaProjection;
    @Nullable
    private static ImageReader mImageReader;
    @Nullable
    private static VirtualDisplay mVirtualDisplayImageReader;
    @Nullable
    private static VirtualDisplay mVirtualDisplayProjection;
    @Nullable
    private static MediaRecorder mMediaRecorder;
    public static int resultCode;
    public static Intent resultData;
    private static boolean mImageAvailable = false;
    public static int serviceType = ServiceType.SCREENSHOT;
    public static boolean running = false;

    private static final MediaProjection.Callback MEDIA_PROJECTION_CALLBACK = new MediaProjection.Callback() {
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (serviceType == ServiceType.SCREENSHOT) {
            NotificationHelper.startMediaProjectionForeground(this, "截图");
            mMediaProjection = MediaProjectionHelper.getManager().getMediaProjection(resultCode, resultData);
            createImageReaderVirtualDisplay();
        } else if (serviceType == ServiceType.PROJECTION) {
            NotificationHelper.startMediaProjectionForeground(this, "投屏");
            mMediaProjection = MediaProjectionHelper.getManager().getMediaProjection(resultCode, resultData);
            createProjectionVirtualDisplay();
        } else if (serviceType == ServiceType.VIDEO) {
            NotificationHelper.startMediaProjectionForeground(this, "录屏");
            mMediaProjection = MediaProjectionHelper.getManager().getMediaProjection(resultCode, resultData);
            createVideoVirtualDisplay();
        }
        running = true;
    }

    /**
     * 这是截屏用的
     */
    private static void createImageReaderVirtualDisplay() {
        if (mMediaProjection == null) {
            Log.e(TAG, "mMediaProjection is null");
            return;
        }

        DisplayMetrics dm = WindowHelper.getRealMetrics();
//        mImageReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 10);
        try {
            mImageReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2); // 尝试增加缓冲区数量
        } catch (Exception e) {
            Log.e(TAG, "Failed to create ImageReader: " + e.getMessage());
            // 如果创建失败，考虑降级处理或其他替代方案
        }
        mImageReader.setOnImageAvailableListener(reader -> {
                Log.e(TAG, "Image available in ImageReader");
                mImageAvailable = true;
            }, null);
        mMediaProjection.registerCallback(MEDIA_PROJECTION_CALLBACK, null);

        mVirtualDisplayImageReader = mMediaProjection.createVirtualDisplay("ImageReader",
                dm.widthPixels,
                dm.heightPixels,
                dm.densityDpi,
                Display.FLAG_ROUND,
                mImageReader.getSurface(),
                null,
                null);
    }


    public static void createProjectionVirtualDisplay() {

        if (mMediaProjection != null && ProjectionView.isSurfaceCreated()) {

            //
            DisplayMetrics dm = WindowHelper.getRealMetrics();
            if (mVirtualDisplayProjection != null) {
                mVirtualDisplayProjection.release();
            }

            //
            Surface surface = WindowHelper.getProjectionSurface();

            mMediaProjection.registerCallback(MEDIA_PROJECTION_CALLBACK, null);

            //
            mVirtualDisplayProjection = mMediaProjection.createVirtualDisplay(
                    "Projection",
                    dm.widthPixels,
                    dm.heightPixels,
                    dm.densityDpi,
                    Display.FLAG_ROUND,
                    surface,
                    null,
                    null);
        }
    }

    public static void createVideoVirtualDisplay() {
        if (mMediaProjection != null) {
            if (mMediaRecorder != null) {
                mMediaRecorder.reset();
                mMediaRecorder.release();
            }
            mMediaProjection.registerCallback(MEDIA_PROJECTION_CALLBACK, null);
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mMediaRecorder.setOutputFile(App.getApp().getExternalFilesDir(null).getParent() + "/" + createVideoFileName());
            DisplayMetrics dm = WindowHelper.getRealMetrics();
            mMediaRecorder.setVideoSize(dm.widthPixels, dm.heightPixels);
            mMediaRecorder.setVideoFrameRate(60);
            // 高一些，保证清晰度
            mMediaRecorder.setVideoEncodingBitRate(10 * 1024 * 1024);
        }
    }

    public static void startVideoRecord() {
        if (mMediaProjection != null && mMediaRecorder != null) {
            try {
                mMediaRecorder.prepare();
                Surface surface = mMediaRecorder.getSurface();
                DisplayMetrics dm = WindowHelper.getRealMetrics();
                mMediaProjection.createVirtualDisplay("Video", dm.widthPixels, dm.heightPixels,
                        dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null);
                mMediaRecorder.start();
            } catch (IOException ignore) {
            }
        }
    }

    public static void stopVideoRecord() {
        if (mMediaRecorder != null) {
            mMediaRecorder.stop();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    public static void screenshot() {
        if (!mImageAvailable) {
            Log.e(TAG, "screenshot: mImageAvailable is false");
            ToastUtils.shortCall("截屏失败1");
            return;
        }
        if (mImageReader == null) {
            Log.e(TAG, "screenshot: mImageReader is null");
            ToastUtils.shortCall("截屏失败2");
            return;
        }
        try {
            Image image = mImageReader.acquireLatestImage();

            // 获取数据
            int width = image.getWidth();
            int height = image.getHeight();
            final Image.Plane plane = image.getPlanes()[0];
            final ByteBuffer buffer = plane.getBuffer();

            // 重新计算Bitmap宽度，防止Bitmap显示错位
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            int bitmapWidth = width + rowPadding / pixelStride;

            // 创建Bitmap
            Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            // 释放资源
            image.close();

            // 裁剪Bitmap，因为重新计算宽度原因，会导致Bitmap宽度偏大
            Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();

            String fileName = createScreenshotFileName();
            File file = new File(App.getApp().getExternalFilesDir(null).getParent(), fileName);
            Log.i(TAG,file.getAbsolutePath());

            if (!file.exists()) {
                file.createNewFile();
            }
            FileOutputStream fos = new FileOutputStream(file);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            result.compress(Bitmap.CompressFormat.PNG, 100, bos);
            bos.close();
            result.recycle();
            ToastUtils.longCall("截图成功！"+fileName);
        } catch (IOException e) {
            e.printStackTrace();
            ToastUtils.longCall("截图失败！");
        }
    }

    private static String createScreenshotFileName() {
        return "screenshot-"+getDateStr()+".png";
    }

    private static String createVideoFileName() {
        return "Video-"+getDateStr()+".mp4";
    }

    private static String getDateStr() {
        Date date = new Date();
        return String.valueOf(date.getTime());
//        Calendar calendar = Calendar.getInstance(Locale.CHINA);
//        int year = calendar.get(Calendar.YEAR);
//        int month = calendar.get(Calendar.MONTH) + 1;
//        int day = calendar.get(Calendar.DAY_OF_MONTH);
//        int hour = calendar.get(Calendar.HOUR_OF_DAY);
//        int minute = calendar.get(Calendar.MINUTE);
//        int second = calendar.get(Calendar.SECOND);
//        return String.format("%d%02d%02d%02d%02d%02d", year, month, day, hour, minute, second);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mVirtualDisplayImageReader != null) {
            mVirtualDisplayImageReader.release();
            mVirtualDisplayImageReader = null;
        }
        if (mVirtualDisplayProjection != null) {
            mVirtualDisplayProjection.release();
            mVirtualDisplayProjection = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        mImageAvailable = false;
        if (mMediaProjection != null) {
            mMediaProjection.unregisterCallback(MEDIA_PROJECTION_CALLBACK);
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        running = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}