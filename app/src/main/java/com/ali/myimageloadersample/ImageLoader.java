package com.ali.myimageloadersample;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by mumu on 2019/4/18.
 */

public class ImageLoader {

    private static final int MAX_MEMORY = (int) (Runtime.getRuntime().maxMemory() / 8);
    private static final int MAX_DISK = 10 * 1024 * 1024;
    private static final int LOAD_FORM_NET_RESULT = 0x1;
    private static ImageLoader mImageLoader;

    private LruCache<String, Bitmap> mLruChe;
    private File mDiskRootDirector;
    private DiskLruCache mDiskLruche;
    private MessageDigest messageDigest;
    //线程池
    private ExecutorService mExecutor;
    private Handler mHandler = new Handler();


    public ImageLoader() {
        mLruChe = new LruCache<String, Bitmap>(MAX_MEMORY) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        mDiskRootDirector = new File(Environment.getExternalStorageDirectory(), "images");
        if (!mDiskRootDirector.exists()) {
            mDiskRootDirector.mkdirs();
        }
        try {
            Log.e("TAG", Environment.getExternalStorageState());
            mDiskLruche = DiskLruCache.open(mDiskRootDirector, 1, 1, MAX_DISK);
        } catch (IOException e) {
            e.printStackTrace();
        }

        mExecutor = Executors.newFixedThreadPool(5);
    }

    public static ImageLoader getInstance() {
        if (mImageLoader == null) {
            synchronized (ImageLoader.class) {
                if (mImageLoader == null) {
                    mImageLoader = new ImageLoader();
                }
            }

        }
        return mImageLoader;
    }

    public void display(String url, ImageView imageView) {
        Bitmap bitmap = loadFromMemory(url);
        if (bitmap == null) {
            bitmap = loadFromDisk(url);
            if (bitmap != null) {
                saveToMemory(url, bitmap);
            } else {
                loadFromNet(imageView, url);
            }
        }
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            Log.e("TAG", "最终加载的图片宽高是" + bitmap.getWidth() + "x" + bitmap.getHeight());
        }
    }

    private void saveToMemory(String url, Bitmap bitmap) {
        Log.e("TAG", "saveToMemory");
        mLruChe.put(getMessageDigest(url), bitmap);
    }

    private void saveToDisk(String url, Bitmap bitmap) {
        Log.e("TAG", "saveToDisk");
        OutputStream outputStream;
        try {
            DiskLruCache.Editor editor = mDiskLruche.edit(getMessageDigest(url));
            outputStream = editor.newOutputStream(0);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream);
            editor.commit();
            mDiskLruche.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadFromNet(ImageView imageView, String bitmapUrl) {
        Log.e("TAG", "loadFromNet");
        mExecutor.submit(new LoaderTask(imageView, bitmapUrl, this));
    }

    static class LoaderTask implements Runnable {
        ImageView imageView;
        String bitmapUrl;
        private final WeakReference<ImageLoader> handlerWeakReference;

        public LoaderTask(ImageView imageView, String bitmapUrl, ImageLoader imageLoader) {
            handlerWeakReference = new WeakReference<>(imageLoader);
            this.imageView = imageView;
            this.bitmapUrl = bitmapUrl;
        }

        @Override
        public void run() {
            //获取inputStream
            InputStream inputStream = getStreamFormUrl(bitmapUrl);
            //二次采样
            BitmapFactory.Options sampleOption = getSampleOption(imageView, inputStream);
            //重新获取inputStream      ps：inputstream只能用一次，第二次使用会有问题
            InputStream secondInputStream = getStreamFormUrl(bitmapUrl);
            //获取最终的bitmap
            final Bitmap bitmap = BitmapFactory.decodeStream(secondInputStream, null, sampleOption);
            if (bitmap == null) {
                return;
            }
            final ImageLoader imageLoader = handlerWeakReference.get();
            if (imageLoader != null && imageLoader.mHandler != null) {
                imageLoader.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        imageView.setImageBitmap(bitmap);
                        imageLoader.saveToDisk(bitmapUrl, bitmap);
                        imageLoader.saveToMemory(bitmapUrl, bitmap);
                    }
                });
            }
        }

        //计算缩放比，设置给option
        public BitmapFactory.Options getSampleOption(ImageView imageView, InputStream inputStream) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            //开启只采宽高模式
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            int outWidth = options.outWidth;
            int outHeight = options.outHeight;
            int width = imageView.getWidth();
            int height = imageView.getHeight();
            int sample = 1;
            //计算缩放比
            while (outWidth / sample > width || outHeight / sample > height) {
                sample *= 2;
            }
            //设置最终的缩放比、关闭只采宽高模式
            options.inSampleSize = sample;
            options.inJustDecodeBounds = false;
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return options;
        }

        InputStream getStreamFormUrl(String bitmapUrl) {
            try {
                URL url;
                url = new URL(bitmapUrl);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setConnectTimeout(5000);
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();
                if (urlConnection.getResponseCode() == 200) {
                    return urlConnection.getInputStream();
                }
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }


    private Bitmap loadFromDisk(String url) {
        Log.e("TAG", "loadFromDisk");
        String key = getMessageDigest(url);
        Bitmap bitmap = null;
        InputStream inputStream = null;
        try {
            DiskLruCache.Snapshot snapshot = mDiskLruche.get(key);
            if (snapshot == null) {
                return null;
            }
            inputStream = snapshot.getInputStream(0);
            bitmap = BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return bitmap;
    }

    private Bitmap loadFromMemory(String url) {
        Log.e("TAG", "loadFromMemory");
        return mLruChe.get(getMessageDigest(url));
    }

    //Md5加密
    private String getMessageDigest(String message) {
        String digestString;
        try {
            messageDigest = MessageDigest.getInstance("MD5");
            //更新数据
            messageDigest.update(message.getBytes());
            //计算hash
            byte[] digests = messageDigest.digest();
            //将byte数据转为16进制字符串
            digestString = bytesToHexString(digests);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            //如果异常的话直接使用自己的hashCode
            digestString = String.valueOf(message.hashCode());
        }
        return digestString;
    }

    //将byte数组转换为16进制的表现形式
    private String bytesToHexString(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            //将int转换为16进制形式
            String result = Integer.toHexString(0xFF & b);
            if (result.length() < 1) {
                sb.append("0");
            }
            sb.append(result);
        }
        return sb.toString();
    }


    public String getFileNameByUrl(String url) {
        return url.substring(url.lastIndexOf("/") + 1);
    }
}
