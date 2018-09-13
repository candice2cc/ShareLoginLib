package com.liulishuo.share;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.liulishuo.share.content.ShareContent;
import com.liulishuo.share.content.ShareContentPic;
import com.liulishuo.share.qq.QQPlatform;
import com.liulishuo.share.utils.EventHandlerActivity;
import com.liulishuo.share.utils.IPlatform;
import com.liulishuo.share.utils.SlUtils;
import com.liulishuo.share.weibo.WeiBoPlatform;
import com.liulishuo.share.weixin.WeiXinPlatform;
import com.sina.weibo.sdk.exception.WeiboException;
import com.sina.weibo.sdk.net.RequestListener;
import com.sina.weibo.sdk.utils.LogUtil;
import com.tencent.open.utils.HttpUtils;
import com.tencent.tauth.IRequestListener;

import org.apache.http.conn.ConnectTimeoutException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author Kale
 * @date 2018/9/11
 */
public class ShareLoginLib {

    public static final String TAG = "ShareLoginLib";

    private static Map<String, String> sMap;

    private static boolean DEBUG = false;

    public static String APP_NAME, TEMP_PIC_DIR;

    private static List<Class<? extends IPlatform>> supportPlatforms;

    public static EventHandlerActivity.OnCreateListener onCreateListener;

    private static IPlatform curPlatform;

    private static EventHandlerActivity sEventHandlerActivity;

    public static void init(Application application, @Nullable String curAppName, @Nullable String tempPicDir, boolean debug) {
        APP_NAME = curAppName;

        if (TextUtils.isEmpty(tempPicDir)) {
            TEMP_PIC_DIR = SlUtils.generateTempPicDir(application);
        }

        DEBUG = debug;

        if (DEBUG) {
            LogUtil.enableLog();
        } else {
            LogUtil.disableLog();
        }
    }

    public static void initPlatforms(Map<String, String> keyValue, List<Class<? extends IPlatform>> platforms) {
        sMap = keyValue;
        supportPlatforms = platforms;
    }

    public static void doLogin(@NonNull final Activity activity, String type, @Nullable LoginListener listener) {
        doAction(activity, true, type, null, listener, null);
    }

    public static void doShare(@NonNull final Activity activity, String type, @NonNull ShareContent shareContent, @Nullable ShareListener listener) {
        if (shareContent instanceof ShareContentPic) {
            // 针对小图和大图做针对性的处理
            ShareContentPic content = (ShareContentPic) shareContent;
            // 压缩图片大小至符合要求的size
            content.setThumbBmpBytes(SlUtils.getImageThumbByteArr(content.getThumbBmp()));
            // 将大图保存到磁盘中，供第三方app进行读取
            content.setLargeBmpPath(SlUtils.saveBitmapToFile(content.getLargeBmp(), SlUtils.getTempPicFilePath()));

            shareContent = content;
        }

        doAction(activity, false, type, shareContent, null, listener);
    }

    private static void doAction(Activity activity, boolean isLoginAction, String type, @Nullable ShareContent content,
            LoginListener loginListener, ShareListener shareListener) {

        // 1. 得到目前支持的平台列表
        ArrayList<IPlatform> platforms = new ArrayList<>();

        for (Class<? extends IPlatform> platformClz : supportPlatforms) {
            try {
                platforms.add(platformClz.newInstance());
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        // 2. 根据type匹配出一个目标平台
        for (IPlatform platform : platforms) {
            for (String s : platform.getSupportedTypes()) {
                if (s.equals(type)) {
                    curPlatform = platform;
                    break;
                }
            }
        }

        // 3. 初始化监听器
        if (loginListener == null) {
            loginListener = new LoginListener();
        }

        if (shareListener == null) {
            shareListener = new ShareListener();
        }

        // 4. 检测当前运行环境，看是否正常
        try {
            if (curPlatform == null) {
                throw new UnsupportedOperationException("未找到支持该操作的平台");
            } else {
                curPlatform.checkEnvironment(activity, type, content != null ? content.getType() : ShareContent.NO_CONTENT);
            }
        } catch (Throwable throwable) {
            if (isLoginAction) {
                loginListener.onError(throwable.getMessage());
            } else {
                shareListener.onError(throwable.getMessage());
            }
            return;
        }

        // 5. 启动辅助的activity，最终执行具体的操作

        final LoginListener finalLoginListener = loginListener;
        final ShareListener finalShareListener = shareListener;

        SlUtils.startActivity(activity, new Intent(activity, EventHandlerActivity.class), eventActivity -> {
            sEventHandlerActivity = eventActivity;

            if (isLoginAction) {
                curPlatform.doLogin(eventActivity, finalLoginListener);
            } else {
                assert content != null;
                curPlatform.doShare(eventActivity, type, content, finalShareListener);
            }
        });
    }

    public static void onActivityCreate(EventHandlerActivity activity) {
        onCreateListener.onCreate(activity);
    }

    public static IPlatform getCurPlatform() {
        return curPlatform;
    }

    public static void destroy() {
        curPlatform = null;
        onCreateListener = null;
        sEventHandlerActivity = null;
    }

    public static String getValue(String key) {
        return sMap.get(key);
    }

    public static void printLog(String message) {
        if (DEBUG) {
            Log.i(TAG, "======> " + message);
        }
    }

    public static void printErr(String message) {
        if (DEBUG) {
            Log.e(TAG, message);
        }
    }

    public static void checkLeak() {
        new Handler().postDelayed(() -> {
            if (sEventHandlerActivity != null) {
                throw new RuntimeException("内存泄漏了");
            } else {
                printLog("没有泄漏，EventHandlerActivity已经正常关闭");
            } 
            
        }, 2000);
    }

    public static boolean isQQInstalled(Context context) {
        return new QQPlatform().isAppInstalled(context);
    }

    public static boolean isWeiBoInstalled(Context context) {
        return new WeiBoPlatform().isAppInstalled(context);
    }

    public static boolean isWeiXinInstalled(Context context) {
        return new WeiXinPlatform().isAppInstalled(context);
    }

    public abstract static class UserInfoListener implements RequestListener {

        private LoginListener listener;

        protected UserInfoListener(LoginListener listener) {
            this.listener = listener;
        }

        @Override
        public void onComplete(String json) {
            OAuthUserInfo userInfo = null;
            try {
                userInfo = json2UserInfo(new JSONObject(json));
            } catch (JSONException e) {
                e.printStackTrace();
                listener.onError(e.getMessage());
            }

            if (userInfo != null) {
                listener.onReceiveUserInfo(userInfo);
            }
        }

        @Override
        public void onWeiboException(WeiboException e) {
            e.printStackTrace();
            listener.onError(e.getMessage());
        }

        public abstract OAuthUserInfo json2UserInfo(JSONObject jsonObj) throws JSONException;
    }

    /**
     * http://wiki.open.qq.com/wiki/%E8%8E%B7%E5%8F%96%E7%94%A8%E6%88%B7%E4%BF%A1%E6%81%AF
     *
     * graphPath    要调用的接口名称，通过SDK中的Constant类获取宏定义。
     * params       以K-V组合的字符串参数。Params是一个Bundle类型的参数，里面以键值对（Key-value）的形式存储数据，应用传入的邀请分享等参数就是通过这种方式传递给SDK，然后由SDK发送到后台。
     * httpMethod   使用的http方式，如Constants.HTTP_GET，Constants.HTTP_POST。
     * listener     回调接口，IUiListener实例。
     * state        状态对象，将在回调时原样传回给 listener，供应用识别异步调用。SDK内部不访问该对象。
     */
    public class MyRequestListener implements IRequestListener {

        @Override
        public void onComplete(JSONObject jsonObject) {

        }

        @Override
        public void onIOException(IOException e) {

        }

        @Override
        public void onMalformedURLException(MalformedURLException e) {

        }

        @Override
        public void onJSONException(JSONException e) {

        }

        @Override
        public void onConnectTimeoutException(ConnectTimeoutException e) {

        }

        @Override
        public void onSocketTimeoutException(SocketTimeoutException e) {

        }

        @Override
        public void onNetworkUnavailableException(HttpUtils.NetworkUnavailableException e) {

        }

        @Override
        public void onHttpStatusException(HttpUtils.HttpStatusException e) {

        }

        @Override
        public void onUnknowException(Exception e) {

        }
    }


}