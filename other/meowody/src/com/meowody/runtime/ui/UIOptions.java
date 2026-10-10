package com.meowody.runtime.ui;

import com.badlogic.gdx.scenes.scene2d.Action;

import java.util.function.Function;

/**
 * UI配置选项，控制UI的行为、生命周期、动画和缓存。
 *
 * @param <T> UI的具体类型，必须继承BaseUI
 */
public final class UIOptions<T extends BaseUI> {
    /**
     * UI类型：PAGE（页面，全屏）、MODAL（模态框）、WINDOW（浮窗）。默认WINDOW
     */
    public UIKind kind = UIKind.WINDOW;

    /**
     * 是否允许同时打开多个相同路径的UI实例。默认false，重复打开会复用现有实例
     */
    public boolean multiple;

    /**
     * 打开新UI时是否保持现有PAGE可见。仅对PAGE有效。默认false，新PAGE会隐藏旧PAGE
     */
    public boolean persistent;

    /**
     * 是否将UI缓存。关闭时若为true则存入缓存，再次打开时复用；为false则销毁。默认true
     */
    public boolean cache = true;

    /**
     * 返回键按下时是否自动关闭此UI。仅PAGE/MODAL有效。默认true
     */
    public boolean dismissOnBack = true;

    /**
     * 动画执行的最长等待时间（秒）。超过此时间则强制关闭动画。默认30秒
     */
    public double animationTimeout = 30;

    /**
     * 进入动画回调。参数为UI实例，返回Action动画对象。若返回null则无动画。仅在open时执行
     */
    public Function<T, Action> enterAnimation;

    /**
     * 退出动画回调。参数为UI实例，返回Action动画对象。若返回null则无动画。仅在close时执行
     */
    public Function<T, Action> exitAnimation;

    /**
     * JSON资源的根路径。用于解析JSON中的相对依赖路径（如图片、音频）。默认为JSON文件所在目录
     */
    public String assetRoot;

    /**
     * 远程资源包URL。用于加载远程UI资源包。为空则加载本地资源。默认""
     */
    public String packUrl = "";

    public UIOptions<T> copy() {
        UIOptions<T> o = new UIOptions<>();
        o.kind = kind;
        o.multiple = multiple;
        o.persistent = persistent;
        o.cache = cache;
        o.dismissOnBack = dismissOnBack;
        o.animationTimeout = animationTimeout;
        o.enterAnimation = enterAnimation;
        o.exitAnimation = exitAnimation;
        o.assetRoot = assetRoot;
        o.packUrl = packUrl;
        return o;
    }
}
