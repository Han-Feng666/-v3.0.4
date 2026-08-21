package com.HanFeng.service

import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/**
 * P1.6 WebView 广告拦截增强
 * 
 * 功能：
 * 1. 反广告拦截检测绕过
 * 2. 广告元素自动隐藏
 * 3. 广告请求拦截
 * 4. 弹窗广告阻止
 */
object WebViewAdBlocker {
    private const val TAG = "WebViewAdBlocker"
    
    // 已注入的 WebView（弱引用避免内存泄漏）
    private val injectedWebViews = ConcurrentHashMap<Int, Boolean>()
    
    // 反广告拦截检测脚本
    private const val ANTI_ADBLOCK_BYPASS = """
(function() {
    // 隐藏广告拦截检测弹窗
    const originalConfirm = window.confirm;
    const originalAlert = window.alert;
    
    window.confirm = function(msg) {
        if (msg && (msg.toLowerCase().includes('adblock') || msg.toLowerCase().includes('广告拦截'))) {
            return false;
        }
        return originalConfirm.call(this, msg);
    };
    
    window.alert = function(msg) {
        if (msg && (msg.toLowerCase().includes('adblock') || msg.toLowerCase().includes('广告拦截'))) {
            return;
        }
        return originalAlert.call(this, msg);
    };
    
    // 屏蔽广告检测函数
    const blockedDetectors = [
        'checkAdBlock', 'detectAdblock', 'isAdblock', 'adblockDetected',
        'showAdBlockMessage', 'onAdBlockDetected'
    ];
    
    blockedDetectors.forEach(funcName => {
        if (typeof window[funcName] === 'function') {
            window[funcName] = function() { return false; };
        }
    });
    
    // 屏蔽常见广告检测库
    if (typeof window.BlockAdblockTech !== 'undefined') {
        window.BlockAdblockTech = function() { return false; };
    }
})();
"""

    // 广告元素自动隐藏脚本
    private const val AUTO_HIDE_ADS = """
(function() {
    const adSelectors = [
        // 常见广告类名
        '.ad', '.ads', '.advert', '.advertisement',
        '.ad-banner', '.ad-banner-container',
        '.ad-container', '.ad-content', '.ad-wrapper',
        '.ad-sidebar', '.ad-footer', '.ad-header',
        '.banner-ad', '.text-ad', '.image-ad',
        '.google-ad', '.adsbygoogle',
        '[class*="ad-container"]', '[class*="ad-banner"]',
        '[class*="advert"]', '[class*="sponsor"]',
        
        // 常见广告 ID
        '#ad', '#ads', '#advert', '#advertisement',
        '#ad-banner', '#ad-container', '#ad-wrapper',
        '#google-ad', '[id*="ad-container"]',
        
        // 浮层/弹窗广告
        '.popup-ad', '.popunder', '.overlay-ad',
        '[class*="popup"]', '[class*="overlay"]',
        
        // 信息流广告
        '.feed-ad', '.feed-advert', '.native-ad',
        '[class*="feed-ad"]', '[class*="native-ad"]',
        
        // 视频贴片广告
        '.video-ad', '.pre-roll', '.mid-roll', '.post-roll',
        '[class*="video-ad"]', '[class*="pre-roll"]',

        // 国内广告联盟 - 穿山甲
        '[class*="pangle"]', '[id*="pangle"]', '[class*="csj"]',
        '[class*="bytedance"]', '[class*="pangolin"]',
        
        // 国内广告联盟 - 广点通
        '[class*="gdt"]', '[class*="guangdiantong"]',
        '[class*="tencentad"]', '[id*="gdt"]',
        
        // 国内广告联盟 - 快手
        '[class*="kuaishou"]', '[class*="ksad"]',
        '[class*="kwaiad"]',
        
        // 国内广告联盟 - 百度
        '[class*="baiduad"]', '[class*="bdad"]',
        '[class*="cpro"]', '[id*="baiduad"]',
        
        // 国内广告联盟 - 阿里/淘宝
        '[class*="taobao-ad"]', '[class*="alimama"]',
        '[class*="tb-ad"]', '[class*="aliad"]',
        
        // 国内广告联盟 - 小米/华为/OPPO/VIVO
        '[class*="xiaomiad"]', '[class*="huaweiad"]',
        '[class*="oppoad"]', '[class*="vivoad"]', '[class*="moad"]',
        
        // 国内广告联盟 - 其他
        '[class*="sigmob"]', '[class*="mintegral"]',
        '[class*="unityads"]', '[class*="applovin"]',
        '[class*="vungle"]', '[class*="ironsource"]',
        '[class*="tradplus"]', '[class*="topon"]',
        
        // 激励视频广告
        '[class*="reward"]', '[class*="rewarded"]', '[class*="reward-video"]',
        
        // 开屏广告
        '[class*="splash"]', '[class*="splash-ad"]', '[class*="launch-ad"]',
        '[class*="start-ad"]', '[class*="open-ad"]', '[id*="splash"]',
        
        // 插屏广告
        '[class*="interstitial"]', '[class*="interad"]',
        '[class*="fullscreen-ad"]', '[class*="full-ad"]',
        
        // 横幅广告
        '[class*="banner"]:not([class*="top-banner"]):not([class*="nav-banner"])',
        '[class*="float-ad"]', '[class*="float-banner"]',
        '[class*="fixed-bottom"]', '[class*="sticky-bottom"]',
        '[class*="bottom-banner"]',
        
        // 应用内推广
        '[class*="promote"]', '[class*="promotion"]',
        '[class*="recommend-ad"]', '[class*="hot-ad"]',
        '[class*="guess-you-like"]', '[class*="recommend"]',
        
        // 国内应用通用广告容器
        '[class*="ad-view"]', '[class*="ad-item"]', '[class*="ad-list"]',
        '[class*="ad-cell"]', '[class*="ad-card"]', '[class*="ad-module"]',
        '[class*="ad-tag"]', '[class*="ad-mark"]', '[class*="ad-label"]',
        
        // 下载类广告
        '[class*="download-ad"]', '[class*="app-download"]',
        '[class*="install-ad"]', '[class*="game-download"]',
        
        // 淘宝/京东/拼多多等电商推广
        '[class*="tmall-ad"]', '[class*="jd-ad"]', '[class*="pdd-ad"]',
        '[class*="coupon-ad"]', '[class*="promo-ad"]', '[class*="sale-ad"]',
        
        // 信息流/推荐流广告
        '[class*="stream-ad"]', '[class*="feed-item"]', '[class*="recommend-item"]',
        '[class*="ad-recommend"]', '[class*="ad-feed"]',
        
        // 侧边栏/悬浮广告
        '[class*="sidebar-ad"]', '[class*="float-ad"]', '[class*="fix-ad"]',
        '[class*="slide-ad"]', '[class*="corner-ad"]', '[class*="flying-ad"]',
        
        // 底部/顶部广告
        '[class*="bottom-ad"]', '[class*="top-ad"]', '[class*="header-ad"]',
        '[class*="footer-ad"]', '[class*="middle-ad"]',
        
        // 贴片/原生广告
        '[class*="native-ad"]', '[class*="native-ad-item"]',
        '[class*="native-banner"]', '[class*="native-feed"]',
        
        // 常见广告 iframe/embed
        'iframe[src*="doubleclick"]', 'iframe[src*="googleads"]',
        'iframe[src*="adservice"]', 'iframe[src*="adsystem"]',
        'iframe[src*="advert"]', 'iframe[src*="ad."]',
        'iframe[src*="ad-"]',
        
        // 应用内网页广告通用
        '[data-ad]', '[data-ad-id]', '[data-ad-slot]',
        '[data-google-query-id]', '[data-ad-format]',
        'ins.adsbygoogle',
        
        // 更多国内广告联盟/聚合 SDK
        '[class*="gromore"]', '[class*="topon"]', '[class*="ksad"]',
        '[class*="weishang"]', '[class*="bksd"]', '[class*="qqad"]',
        '[class*="tencent-ads"]', '[class*="qad"]', '[class*="yqs"]',
        '[class*="admaster"]', '[class*="adview"]', '[class*="adsage"]',
        '[class*="mobads"]', '[class*="baidu-ads"]', '[class*="adwo"]',
        '[class*="sigmob-ad"]', '[class*="mintegral-ad"]',
        '[class*="beizi"]', '[class*="yueme"]', '[class*="westat"]',
        '[class*="trackview"]', '[class*="tdmark"]', '[class*="adgate"]',
        
        // 美团/饿了么/电商生活类广告
        '[class*="mtad"]', '[class*="meituan-ad"]', '[class*="ele-ad"]',
        '[class*="koubei-ad"]', '[class*="pdd-ad"]', '[class*="vip-ad"]',
        '[class*="suning-ad"]', '[class*="gome-ad"]',
        
        // 微博/知乎/内容平台广告
        '[class*="weibo-ad"]', '[class*="wb-ad"]', '[class*="zhihu-ad"]',
        '[class*="promo-banner"]', '[class*="article-ad"]',
        '[class*="infeed-ad"]', '[class*="content-ad"]',
        
        // 海外主流广告容器
        '[class*="facebook-ad"]', '[class*="instory-ad"]',
        '[class*="twitter-ad"]', '[class*="tiktok-ad"]',
        '[class*="snapchat-ad"]', '[class*="reddit-ad"]',
        '[class*="linkedin-ad"]', '[class*="pinterest-ad"]',
        '[class*="youtube-ad"]', '[class*="ytp-ad-"]',
        '[class*="google-ad"]', '[class*="g-ad"]',
        
        // 视频/直播贴片广告容器
        '[class*="video-ad-container"]', '[class*="player-ad"]',
        '[class*="livestream-ad"]', '[class*="vod-ad"]',
        '[class*="ad-pause"]', '[class*="pause-ad"]',
        
        // 卡片/弹层/插屏广告
        '[class*="ad-pop"]', '[class*="ad-modal"]', '[class*="ad-dialog"]',
        '[class*="ad-toast"]', '[class*="ad-float"]',
        '[class*="ad-layer"]', '[class*="ad-mask"]',
        
        // 京东/淘宝直播推广
        '[class*="live-ad"]', '[class*="anchor-ad"]',
        '[class*="shop-ad"]', '[class*="store-ad"]'
    ];
    
    function hideAds() {
        adSelectors.forEach(selector => {
            try {
                const elements = document.querySelectorAll(selector);
                elements.forEach(el => {
                    el.style.display = 'none';
                    el.style.visibility = 'hidden';
                    el.style.opacity = '0';
                    el.style.pointerEvents = 'none';
                });
            } catch (e) {
                // Ignore invalid selectors
            }
        });
    }
    
    // 立即执行一次
    hideAds();
    
    // 使用 MutationObserver 监听 DOM 变化，动态移除广告
    const observer = new MutationObserver((mutations) => {
        let shouldHide = false;
        mutations.forEach(mutation => {
            if (mutation.addedNodes.length > 0) {
                shouldHide = true;
            }
        });
        
        if (shouldHide) {
            hideAds();
        }
    });
    
    observer.observe(document.body, {
        childList: true,
        subtree: true
    });
})();
"""

    // 广告请求拦截脚本
    private const val AD_REQUEST_BLOCKER = """
(function() {
    const originalFetch = window.fetch;
    const originalXHROpen = XMLHttpRequest.prototype.open;
    const originalXHRSend = XMLHttpRequest.prototype.send;
    
    const domainBlockList = [
        // === 国际广告平台 ===
        'doubleclick.net', 'adservice.google.com', 'googleadservices.com',
        'googlesyndication.com', 'adsbygoogle.com', 'google-analytics.com',
        'googletagmanager.com', 'googletagservices.com', 'googleads.g.doubleclick.net',
        'pagead2.googlesyndication.com', 'an.facebook.com', 'pixel.facebook.com',
        'amazon-adsystem.com', 'amazonadsi.com', 'aax.amazon-adsystem.com',
        'ads.yahoo.com', 'yieldmanager.net', 'advertising.yahoo.com',
        'gemini.yahoo.com', 'bs.serving-sys.com', 'adnxs.com', 'adserver.adtech.de',
        'criteo.com', 'criteo.net', 'casalemedia.com',
        'pubmatic.com', 'openx.net', 'rubiconproject.com',
        'appnexus.com', 'indexww.com', 'moatads.com',
        'scorecardresearch.com', 'chartbeat.com', 'quantserve.com',
        'exelator.com', 'bluekai.com', 'demdex.net', 'adsrvr.org',
        'adzerk.net', 'contextweb.com', 'agkn.com',
        'tidaltv.com', 'spotxchange.com', 'adsafeprotected.com',
        'outbrain.com', 'outbrainimg.com', 'taboola.com', 'taboolanews.com',
        'revcontent.com', 'sharethrough.com', 'nativeads.com',
        'smartadserver.com', 'ads.smartadserver.com', 'adform.com',
        'media.net', 'ad.media.net', 'onetag.net', 'sovrn.com',
        'teads.tv', 'ads.teads.tv', 'gumgum.com', 'ads.gumgum.com',
        'triplelift.com', 'ads.triplelift.com', 'bidswitch.net',
        'ads.bidswitch.net', 'dmxleo.com', 'ad.dmxleo.com',
        'xandr.com', 'ads.xandr.com', 'smaato.net', 'ads.smaato.net',
        'adkernel.com', 'ads.adkernel.com', 'admatic.com',
        'adcash.com', 'ads.adcash.com', 'propellerads.com', 'zeropark.com',
        'hilltopads.net', 'popads.net', 'ad.popads.net', 'monetag.com',
        'evadav.com', 'adsterra.com', 'ads.adsterra.com', 'adpushup.com',
        'yieldlove.com', 'adagio.io', 'publift.com', 'pubfood.io',
        'tr.snapchat.com', 'ads.snapchat.com', 'ads.twitter.com',
        'analytics.twitter.com', 'ads.pinterest.com', 'ad.pinterest.com',
        'adserver.bing.com', 'adcenter.msn.com', 'msads.net',
        'ads.linkedin.com', 'ads.reddit.com', 'ads.youtube.com',
        'googleads.g.doubleclick.net', '2mdn.net', 'doubleclick-analytics.com',
        
        // === 国内广告平台 - 穿山甲/字节 ===
        'pangolin.sdk', 'pangle.io', 'pglstatp.com',
        'byteoversea.com', 'ibyted.com', 'isnssdk.com',
        'slide.to', 'pangle.gg', 'csj.share', 'ad.toutiao.com',
        'ad.douyin.com', 'ad.ppdai.com', 'adbytedance.com',
        
        // === 国内广告平台 - 广点通/腾讯 ===
        'gdt.qq.com', 'ad.qq.com', 'e.qq.com', 'beam.qq.com',
        'btrace.qq.com', 'adshm.qq.com', 'tencentmind.com', 'tencentmart.com',
        'ads.v.qq.com', 'ad.v.qq.com', 'v.gdt.qq.com', 'tgi.qq.com',
        
        // === 国内广告平台 - 快手 ===
        'kuaishou.com/ad', 'ksapisrv.com', 'ads.kuaishou.com',
        'ad.kuaishou.com', 'kwaiad.com', 'gifshow.com/ad',
        'static.kuaishou.com/ad',
        
        // === 国内广告平台 - 百度 ===
        'cpro.baidu.com', 'e.baidu.com', 'nsclick.baidu.com',
        's.click.baidu.com', 'union.baidu.com', 'baidustatic.com',
        'bcebox.com', 'pos.baidu.com', 'cb.baidu.com', 'hm.baidu.com',
        'baiducontent.com', 'bdimg.com/ad', 'bce.bdstatic.com',
        
        // === 国内广告平台 - 阿里/淘宝 ===
        'alimama.com', 'mmstat.com', 'tanx.com', 'ad.taobao.com',
        'click.tmall.com', 'ads.tmall.com', 're.taobao.com',
        'amdc.taobao.com', 'admarket.alibaba.com', 'adash.m.taobao.com',
        'adash-c.taobao.com', 'adash.taobao.com', 'tbopen.m.taobao.com',
        
        // === 国内广告平台 - 京东 ===
        'jdad.com', 'ads.jd.com', 'ad.jd.com', 'jcloud.com/ad',
        'c0.3.cn', 'mp.zhiding.com', 'ad.jdpay.com',
        
        // === 国内广告平台 - 小米 ===
        'ad.mi.com', 'ads.mi.com', 'data.mi.com',
        'new.api.ad.xiaomi.com', 'sdk.ad.xiaomi.com',
        'beacon.sdk.xiaomi.com', 'micode.mi.com', 'mipush.ad.xiaomi.com',
        
        // === 国内广告平台 - 华为 ===
        'ad.huawei.com', 'ads.huawei.com', 'adserver.huawei.com',
        'hiscene.com', 'ad.hicloud.com', 'ads.hicloud.com',
        'appgallery.huawei.com/ad', 'ads.mobile.huawei.com',
        
        // === 国内广告平台 - OPPO/VIVO ===
        'heytapad.com', 'ad.heytap.com', 'ads.heytap.com',
        'ad.oppomobile.com', 'adx.oppomobile.com', 'ad.vivo.com',
        'vivo-ad.com', 'ads.vivo.com',
        
        // === 国内广告平台 - 荣耀/三星/魅族/联想 ===
        'ad.honor.com', 'ads.honor.com', 'ad.samsung.com',
        'ads.samsung.com', 'ad.meizu.com', 'ads.meizu.com',
        'ad.lenovo.com', 'ads.lenovo.com',
        
        // === 国内广告平台 - 门户/内容 ===
        'ad.163.com', 'ads.163.com', 'sdk.ads.yodao.com', 'ad.yodao.com',
        'ad.360.cn', 'ads.360.cn', 'api.ad.360.cn',
        'ad.youku.com', 'ads.youku.com', 'vali.youku.com',
        'ad.iqiyi.com', 'ads.iqiyi.com', 'vd.iqiyi.com',
        'ad.mgtv.com', 'ads.mgtv.com', 'ad.huya.com', 'ads.huya.com',
        'ad.douyu.com', 'ads.douyu.com', 'ad.bilibili.com',
        'ads.bilibili.com', 'cm.bilibili.com', 'ad.zhihu.com',
        'ads.zhihu.com', 'ad.weibo.com', 'ads.weibo.com',
        'adapi.weibo.com', 'adsc.weibo.com', 'ad.sohu.com',
        'ads.sohu.com', 'ad.sina.com.cn', 'ads.sina.com.cn',
        'adsc.sina.com.cn', 'ad.sina.com', 'crs.sina.com',
        'ad.autohome.com.cn', 'ads.autohome.com.cn',
        'ad.meituan.com', 'ad.ele.me', 'ad.58.com', 'ads.58.com',
        'ad.ctrip.com', 'ads.ctrip.com', 'ad.pinduoduo.com',
        'ads.pinduoduo.com', 'ad.vip.com', 'ads.vip.com',
        'ads.suning.com', 'ad.suning.com', 'ad.meitu.com',
        'ads.meitu.com', 'ad.oppo.com',
        
        // === 国内广告平台 - 其他 ===
        'sigmob.com', 'ad.sigmob.com', 'api.sigmob.com',
        'mintegral.com', 'ad.mintegral.com', 'mobi.mintegral.com',
        'vungle.com', 'ad.vungle.com', 'api.vungle.com',
        'applovin.com', 'ad.applovin.com', 'ms.applovin.com',
        'ironsource.com', 'ad.ironsource.com', 'sdk.ironsource.com',
        'ad.unity3d.com', 'ads.unity3d.com', 'tradplusad.com',
        'ad.tradplus.com', 'toponad.com', 'ad.toponad.com',
        'sdk.toponad.com', 'inmobi.com', 'ad.inmobi.com',
        'sdk.inmobi.com', 'mobvista.com', 'ad.mobvista.com',
        'mintegral.net', 'chartboost.com', 'ad.chartboost.com',
        'tapjoy.com', 'ad.tapjoy.com', 'fyber.com',
        'adcolony.com', 'ad.adcolony.com', 'hyprmx.com',
        'startapp.com', 'ad.startapp.com', 'loopme.com',
        'admost.com', 'adgeneration.net', 'nend.net',
        'beizi.com', 'ad.beizi.com', 'gromore.com', 'ad.gromore.com',
        'yueyue.com', 'ad.yueyue.com', 'wesdk.com', 'ad.wesdk.com',
        
        // === 国内统计/推送 ===
        'umeng.com', 'umengcloud.com', 'umtrack.com',
        'bugly.qcloud.com', 'bugly.qq.com',
        'pushwoosh.com', 'growthpush.com',
        'jpush.cn', 'jpush.io', 'jiguang.cn',
        'getui.com', 'getui.net', 'gtpush.cn',
        'igexin.com', 'push.igexin.com', 'mipush.com', 'mi-push.com',
        'heytapmobi.com', 'analytics.126.net', 'cm.cf.umeng.com',
        
        // === 广告联盟/交易所 ===
        'adx.com', 'adx.net', 'adexchange.com', 'adsys.com',
        'adtxt.com', 'adserver.com', 'advertising.com',
        'adnetwork.com', 'adtech.com', 'adform.com', 'adition.com',
        'adscale.com', 'adconductor.com', 'adswizz.com',
        'adpredictive.com', 'adroll.com', 'adbutler.com',
        'adsspace.com', 'adtech.de', 'adserver.de',
        'adserverpub.com', 'adx.io', 'adspirit.net', 'adspirit.de',
        'adssp.cn', 'adssp.com', 'adnet.com', 'adnet.com.cn',
        'adservice.com.cn', 'adservice.net.cn', 'adtrack.com.cn'
    ];
    
    const domainPathRules = [
        ['facebook.com', '/ads'], ['fbcdn.net', '/ads'],
        ['graph.facebook.com', '/ads'], ['instagram.com', '/ads'],
        ['snapchat.com', '/ad'], ['twitter.com', '/ad'],
        ['tiktok.com', '/ad'], ['youtube.com', '/ad'],
        ['reddit.com', '/ads'], ['linkedin.com', '/ads'],
        ['pinterest.com', '/ad'], ['bing.com', '/ad'],
        ['msn.com', '/ad'], ['qq.com', '/ad'], ['qqmail.com', '/ad'],
        ['qlogo.com', '/ad'], ['tencent.com', '/ad'],
        ['kuaishou.com', '/ad'], ['kwai.com', '/ad'],
        ['gifshow.com', '/ad'], ['static.kuaishou.com', '/ad'],
        ['baidu.com', '/ad'], ['bdstatic.com', '/ad'],
        ['baidustatic.com', '/ad'], ['bdimg.com', '/ad'],
        ['taobao.com', '/ad'], ['alibaba.com', '/ad'],
        ['1688.com', '/ad'], ['wapa.taobao.com', '/ad'],
        ['tb.cn', '/ad'], ['fliggy.com', '/ad'], ['etao.com', '/ad'],
        ['jd.com', '/ad'], ['jcloud.com', '/ad'], ['jdpay.com', '/ad'],
        ['mi.com', '/ad'], ['xiaomi.com', '/ad'], ['miui.com', '/ad'],
        ['huawei.com', '/ad'], ['huawei.com', '/ads'],
        ['huaweimobile.com', '/ad'], ['heytap.com', '/ad'],
        ['nearme.com', '/ad'], ['oppomobile.com', '/ad'],
        ['vivo.com', '/ad'], ['unity3d.com', '/ad'],
        ['bytedance.com', '/ad'], ['snssdk.com', '/ad'],
        ['toutiao.com', '/ad'], ['douyin.com', '/ad'],
        ['sohu.com', '/ad'], ['163.com', '/ad'], ['126.com', '/ad'],
        ['sina.com.cn', '/ad'], ['sina.com', '/ad'], ['sinaload.com', '/ad'],
        ['meituan.com', '/ad'], ['dianping.com', '/ad'],
        ['ele.me', '/ad'], ['suning.com', '/ad'], ['pinduoduo.com', '/ad'],
        ['vip.com', '/ad'], ['zhihu.com', '/ad'], ['weibo.com', '/ad'],
        ['t.sina.com.cn', '/ad'], ['bilibili.com', '/ad'],
        ['huya.com', '/ad'], ['douyu.com', '/ad'], ['iqiyi.com', '/ad'],
        ['v.qq.com', '/ad'], ['youku.com', '/ad'], ['mgtv.com', '/ad'],
        ['hicloud.com', '/ad'], ['honor.com', '/ad'],
        ['samsung.com', '/ad'], ['meizu.com', '/ad'], ['lenovo.com', '/ad'],
        ['xiaomi.com', '/push'], ['huawei.com', '/push'],
        ['vivo.com', '/push'], ['oppo.com', '/push'],
        ['meizu.com', '/push'], ['samsung.com', '/push'],
        ['heytap.com', '/push']
    ];
    
    const hostFeatures = [
        'adservice', 'adserver', 'adnetwork', 'advertising',
        'advertisement', 'adtech', 'adunit', 'adsystem', 'adslot',
        'adframe', 'adload', 'adcall', 'adpush', 'adproxy', 'adtxt',
        'adssp', 'adtracker', 'adlog', 'adimp', 'adclick', 'adview',
        'adsrv', 'adnexus', 'admob', 'adsdk', 'adsapi', 'adapi',
        'adengine', 'adacdn', 'adsage', 'admaster', 'adgate',
        'trackview', 'tdmark', 'adx', '-ad-', 'ad_', 'ad1.',
        'banner.', 'popunder', 'popupad', 'interstitial', 'splashad',
        'rewarded', 'rewardvideo', 'advert', 'adservice'
    ];
    
    const pathFeatures = [
        '/ad?', '/ad/', '/ad-', '/ad_', '/ads?', '/ads/',
        '/adserve', '/adserver', '/adview', '/adclick', '/adlog',
        '/adstat', '/adtrack', '/adimp', '/adshow', '/ad_sdk',
        '-ad.', '/adservice', '/adunit', '/adslot', '/adframe',
        '/adload', '/adcall', '/adpush', '/adproxy', '/adtxt',
        '/banner', '/interstitial', '/splash', '/splashad',
        '/splash-ad', '/rewarded', '/rewardvideo', '/reward_video',
        '/popup', '/popunder', '/advert', '/advertising',
        '/advertisement', '/adblock', '/adfetch', '/adstat',
        '/adsense', '/adtech', '/advertise', '/ad-info'
    ];
    
    function getHostInfo(url) {
        try {
            var u = new URL(url);
            return { host: u.hostname.toLowerCase(), path: (u.pathname + u.search).toLowerCase() };
        } catch (e) {
            var lower = url.toLowerCase();
            var m = lower.match(/^[a-z][a-z0-9+.-]*:\/\/([^\/?#]+)/);
            if (m) {
                var rest = lower.slice(m[0].length);
                var slash = rest.indexOf('/');
                return { host: m[1], path: slash >= 0 ? rest.slice(slash) : '' };
            }
            var m2 = lower.match(/^([^\/?#]+)(.*)$/);
            return { host: m2 ? m2[1] : lower, path: m2 ? m2[2] : '' };
        }
    }
    
    function isAdUrl(url) {
        if (!url) return false;
        var info = getHostInfo(url);
        var host = info.host, path = info.path;
        var i;
        for (i = 0; i < domainBlockList.length; i++) {
            var d = domainBlockList[i];
            if (host === d || host.endsWith('.' + d)) return true;
        }
        for (i = 0; i < domainPathRules.length; i++) {
            var rule = domainPathRules[i];
            if ((host === rule[0] || host.endsWith('.' + rule[0])) && path.indexOf(rule[1]) !== -1) return true;
        }
        for (i = 0; i < hostFeatures.length; i++) {
            if (host.indexOf(hostFeatures[i]) !== -1) return true;
        }
        for (i = 0; i < pathFeatures.length; i++) {
            if (path.indexOf(pathFeatures[i]) !== -1) return true;
        }
        return false;
    }
    
    window.fetch = function(url, options) {
        if (isAdUrl(url)) {
            return Promise.resolve(new Response('', { status: 403 }));
        }
        return originalFetch.call(this, url, options);
    };
    
    XMLHttpRequest.prototype.open = function(method, url, ...args) {
        this._url = url;
        return originalXHROpen.apply(this, [method, url, ...args]);
    };
    
    XMLHttpRequest.prototype.send = function(...args) {
        if (this._url && isAdUrl(this._url)) {
            return;
        }
        return originalXHRSend.apply(this, args);
    };
})();
"""

    // 合并所有注入脚本
    private const val ALL_INJECTION_SCRIPT = """
$ANTI_ADBLOCK_BYPASS
$AUTO_HIDE_ADS
$AD_REQUEST_BLOCKER
"""

    /**
     * 注入广告拦截脚本到 WebView
     */
    fun inject(
        webView: WebView,
        enableAntiDetect: Boolean = true,
        enableAutoHide: Boolean = true,
        enableRequestBlock: Boolean = true
    ) {
        if (!webView.isAttachedToWindow) return
        
        val webViewId = webView.hashCode()
        if (injectedWebViews[webViewId] == true) return
        
        // 构建注入脚本
        val scripts = buildString {
            if (enableAntiDetect) append(ANTI_ADBLOCK_BYPASS)
            if (enableAutoHide) append(AUTO_HIDE_ADS)
            if (enableRequestBlock) append(AD_REQUEST_BLOCKER)
        }
        
        if (scripts.isNotEmpty()) {
            webView.evaluateJavascript(scripts, null)
            injectedWebViews[webViewId] = true
            android.util.Log.d(TAG, "Injected ad blocking scripts to WebView $webViewId")
        }
    }

    /**
     * 移除 WebView 的注入脚本记录
     */
    fun remove(webView: WebView) {
        val webViewId = webView.hashCode()
        injectedWebViews.remove(webViewId)
    }

    /**
     * 清理所有注入记录
     */
    fun clear() {
        injectedWebViews.clear()
    }

    /**
     * 获取已注入的 WebView 数量
     */
    fun getInjectedCount(): Int = injectedWebViews.size
}
