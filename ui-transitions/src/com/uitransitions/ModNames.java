package com.uitransitions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「某个界面属于哪个模组」的解析器 —— 纯反射，**不 import 任何加载器 API**。
 *
 * <h2>为什么不能直接 import FabricLoader / ModList</h2>
 *
 * 这个类在共享层（{@code com.uitransitions}），Fabric 与 NeoForge 共用同一份代码，
 * 而 {@code tools/check_shared_code.py} 会拦"共享代码引用加载器专属类"——
 * 那条闸存在的理由很实在：两边加载器的类在对方环境下不存在，import 了就会 NoClassDefFoundError。
 *
 * 所以这里走**按名字反射**：先试 FabricLoader，再试 NeoForge 的 ModList，
 * 两个都不在（或都没这套 API）就返回 null，调用方退回显示包名。
 *
 * <h2>为什么用"类的 code source 路径 ⊂ 模组的根路径"来匹配</h2>
 *
 * `Class.getProtectionDomain().getCodeSource()` 给出该类从哪个 jar/目录加载，
 * 而两个加载器都能列出"每个模组的根路径"。前缀匹配即可，不需要碰任何加载器专有的
 * 模组元数据类型。
 *
 * 已实测（另一个会话）：预热后 `getCodeSource()` 单次约 0.01µs，
 * 所以**不是性能瓶颈**；但结果仍然缓存 —— 那是为了避免在列表重建时反复触发类加载，
 * 不是为了那 0.01µs。
 *
 * <h2>这个类不做的事</h2>
 *
 * 不触发类初始化（`Class.forName(name, false, ...)`）：排除列表里可能列着一个
 * 出问题的界面类，**加载它（更别说初始化它）本身可能有副作用**。
 */
public final class ModNames {

    private ModNames() {
    }

    /** 类名 → 模组显示名；解析不出来就是 null（调用方退回显示包名） */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** 模组根路径 → 模组显示名。首次用到时才扫一遍加载器列表。 */
    private static volatile List<String[]> modRoots;

    /**
     * 是否已经成功扫出过模组列表。
     *
     * **只在成功时置位**：空列表不缓存 —— 否则"第一次调用时加载器还没就绪"会被永久记住，
     * 后面永远显示不出模组名（而这是那种不报错、只能靠真机看列表才发现的失败）。
     */
    private static volatile boolean modsResolved;

    /**
     * 这个界面属于哪个模组。
     *
     * @param className 完整类名
     * @return 模组的显示名；原版、或解析不出来 → null
     */
    public static String modNameFor(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        String cached = CACHE.get(className);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resolved = resolve(className);
        // 空串也是有效缓存：表示"查过了，没有"
        CACHE.put(className, resolved == null ? "" : resolved);
        return resolved;
    }

    private static String resolve(String className) {
        try {
            // 原版类直接判定，不去加载它 —— 省一次类加载，而且原版包名本来就是权威判据
            if (className.startsWith("net.minecraft.")) {
                return null;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, ModNames.class.getClassLoader());
            } catch (Throwable notFound) {
                // 那个界面类不在运行期（配置里可能留着别的版本/别的模组留下的类名）
                return null;
            }
            java.security.CodeSource source;
            try {
                source = type.getProtectionDomain() == null
                        ? null : type.getProtectionDomain().getCodeSource();
            } catch (Throwable ignored) {
                source = null;
            }
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String location = normalize(source.getLocation().getPath());
            for (String[] pair : roots()) {
                if (location.startsWith(pair[0])) {
                    return pair[1];
                }
            }
            if (DEBUG_MODS < 30) {
                DEBUG_MODS++;
                System.out.println("[UI Transitions] 模组名未命中: 类=" + className
                        + " codeSource=" + location
                        + " 已知模组根=" + roots().size() + " 个"
                        + (roots().isEmpty() ? "" : " 首个=" + roots().get(0)[0]
                        + " → " + roots().get(0)[1]));
            }
        } catch (Throwable ignored) {
            // 解析失败只是"显示不出模组名"，绝不影响排除功能本身
        }
        return null;
    }

    /** 诊断计数：只打前若干次，避免刷屏 */
    private static int DEBUG_MODS;

    /**
     * 归一化路径，让两个来源能直接前缀比较。
     *
     * **实测过的坑（Windows）**：`CodeSource.getLocation().getPath()` 给的是
     * `/D:/and/.../cloth.jar`（**带前导斜杠**），而 Fabric 的 `rootPaths` 用
     * `Path.toString()` 给的是 `D:\and\...\cloth.jar`（**反斜杠 + 无前导斜杠**）。
     * 直接 `startsWith` 永远为 false —— 表现就是"模组名一列从来不出现"，
     * 而且**不报错、不崩溃**，只能靠真机看列表才发现。
     *
     * 所以这里：统一分隔符 → 小写（盘符大小写不一致）→ **去掉前导斜杠**。
     */
    private static String normalize(String path) {
        String s = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        // %20 这类 URL 编码必须解码：实测 codeSource 路径里带空格时是
        // `26.3-fabric%200.19.5`，而模组的 rootPath 是带真实空格的 —— 不解码永远匹配不上
        try {
            s = java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 解不开就用原样（顶多少匹配一个）
        }
        // "/d:/..." → "d:/..."：前导斜杠是 URL 形态带来的，文件系统路径没有它
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static List<String[]> roots() {
        if (modsResolved) {
            return modRoots == null ? List.of() : modRoots;
        }
        synchronized (ModNames.class) {
            if (!modsResolved) {
                List<String[]> list = new ArrayList<>();
                collectFabric(list);
                collectNeoForge(list);
                if (list.isEmpty()) {
                    // **不置 modsResolved**：环境还没就绪就下次再试（见字段注释）。
                    // 缓存"空结果"会把一次早期失败变成永久失败。
                    return List.of();
                }
                // 长路径优先：嵌套路径（模组 jar 在别的 jar 里）要排在容器前面
                list.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));
                modRoots = list;
                modsResolved = true;
            }
        }
        return modRoots;
    }

    /** Fabric：FabricLoader.getInstance().getAllMods() → 每个容器的 rootPaths */
    private static void collectFabric(List<String[]> out) {
        Class<?> loaderClass;
        try {
            loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
        } catch (Throwable t) {
            probe("找不到 FabricLoader 类: " + t);
            return;
        }
        Object loader;
        try {
            loader = loaderClass.getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            probe("FabricLoader.getInstance() 失败: " + t);
            return;
        }
        Object mods;
        try {
            mods = loaderClass.getMethod("getAllMods").invoke(loader);
        } catch (Throwable t) {
            probe("getAllMods() 失败: " + t);
            return;
        }
        if (!(mods instanceof Iterable<?> iterable)) {
            probe("getAllMods() 返回的不是 Iterable: " + mods);
            return;
        }
        int count = 0;
        for (Object container : iterable) {
            count++;
            collectOne(out, container);
        }
        probe("Fabric 容器数=" + count + " 收集到的根路径=" + out.size());
    }

    /** 探测阶段的痕迹：只在真的有诊断计数余量时打，且总共不超过几条 */
    private static void probe(String message) {
        if (PROBES < 8) {
            PROBES++;
            System.out.println("[UI Transitions] ModNames 探测: " + message);
        }
    }

    private static int PROBES;

    /** NeoForge：ModList.get().getMods() → 每个 mod 的 owningFile / findResource */
    private static void collectNeoForge(List<String[]> out) {
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            Object modList = modListClass.getMethod("get").invoke(null);
            Object mods = modListClass.getMethod("getMods").invoke(modList);
            if (!(mods instanceof Iterable<?> iterable)) {
                return;
            }
            for (Object modInfo : iterable) {
                // ModInfo 的 API 各版本差异较大，逐条试；任何一条成功就够了
                putIfPresent(out, invokeString(modInfo, "getDisplayName"),
                        invokePath(modInfo, "getOwningFile"));
            }
        } catch (Throwable ignored) {
            // 不是 NeoForge 环境 → 什么都不做
        }
    }

    /** 从一个模组容器对象上取"显示名 + 根路径集合" */
    private static void collectOne(List<String[]> out, Object container) {
        try {
            Object metadata = invokeAny(container, "getMetadata");
            if (metadata == null) {
                return;
            }
            String name = invokeString(metadata, "getName");
            if (name == null) {
                name = invokeString(metadata, "getId");
            }
            if (name == null) {
                return;
            }
            Object paths = invokeAny(container, "getRootPaths");
            if (!(paths instanceof Iterable<?> iterable)) {
                return;
            }
            for (Object path : iterable) {
                String text = String.valueOf(path);
                if (!text.isEmpty()) {
                    out.add(new String[] { normalize(text), name });
                }
            }
        } catch (Throwable t) {
            reportOnce("某个模组容器取不出来（跳过该容器）: " + t);
        }
    }

    /**
     * 沿**接口链**找方法并调用。
     *
     * **实测踩到的坑**：`container.getClass().getMethod("getMetadata")` 会失败 ——
     * Fabric 的容器实现类通常**不是 public**，而 `getMetadata` 是它实现的接口
     * （{@code ModContainer}）上声明的 public 方法。在非 public 类上直接反射调用
     * 会抛 `IllegalAccessException`，方法明明存在却取不到。
     *
     * 沿接口找就没这个问题：接口是 public 的，方法也可访问。
     * （这正是"反射整条链一个模组都收不到、又不报错"的根因。）
     */
    private static Object invokeAny(Object target, String methodName) {
        Class<?> type = target.getClass();
        // 1) 类自己
        try {
            return type.getMethod(methodName).invoke(target);
        } catch (Throwable ignored) {
            // 继续
        }
        // 2) **按名字在整条类型层次里找**（含接口）。
        //    实测：Fabric 的容器实现类不是 public，`getMethod` 拿不到它继承来的 public 方法；
        //    而按名字遍历接口再 invoke，方法明明就在那儿却能被正常调用。
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> iface : allInterfaces(c)) {
                for (java.lang.reflect.Method m : iface.getMethods()) {
                    if (!m.getName().equals(methodName) || m.getParameterCount() != 0) {
                        continue;
                    }
                    try {
                        return m.invoke(target);
                    } catch (Throwable ignored) {
                        // 换下一个候选
                    }
                }
            }
        }
        return null;
    }

    /** 接口 + 它的父接口（递归；Fabric 的容器接口有继承链） */
    private static List<Class<?>> allInterfaces(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        collectInterfaces(type, out);
        return out;
    }

    private static void collectInterfaces(Class<?> type, List<Class<?>> out) {
        if (type == null) {
            return;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (!out.contains(iface)) {
                out.add(iface);
                collectInterfaces(iface, out);
            }
        }
    }

    private static void putIfPresent(List<String[]> out, String name, String path) {
        if (name != null && path != null && !path.isEmpty()) {
            out.add(new String[] { normalize(path), name });
        }
    }

    /**
     * 每个失败原因只打一次。
     *
     * 这类"整条反射链失败"的问题**不报错、不崩溃、只是少一列信息**，
     * 所以必须留下痕迹 —— 但也不能每行列表都刷一遍日志。
     */
    private static void reportOnce(String message) {
        if (REPORTED.add(message)) {
            System.out.println("[UI Transitions] ModNames: " + message);
        }
    }

    private static final java.util.Set<String> REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String invokeString(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String invokePath(Object target, String method) {
        try {
            Object file = target.getClass().getMethod(method).invoke(target);
            if (file == null) {
                return null;
            }
            // ModFileInfo / IModFile 都有 getFilePath()
            Object path = file.getClass().getMethod("getFilePath").invoke(file);
            return path == null ? null : String.valueOf(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 测试与配置重载用：清掉缓存 */
    public static void resetCache() {
        CACHE.clear();
        synchronized (ModNames.class) {
            modRoots = null;
            modsResolved = false;
        }
    }
}
