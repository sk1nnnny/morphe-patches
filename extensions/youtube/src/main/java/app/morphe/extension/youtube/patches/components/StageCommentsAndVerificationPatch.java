/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.morphe.extension.youtube.patches.components;
import static app.morphe.extension.shared.StringRef.str;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.widget.RecyclerView;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.util.LruCache;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.spans.SpanFilter;
import app.morphe.extension.shared.patches.spans.SpanType;
import app.morphe.extension.shared.patches.spans.StringSpanFilterGroup;
import android.view.ViewTreeObserver;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;
@SuppressWarnings("unused")
public final class StageCommentsAndVerificationPatch {
    public enum BadgeType {
        NONE,
        CHECKMARK_100K,
        ARTIST_NOTE;
        public static BadgeType fromString(@Nullable String value) {
            if (value == null) return NONE;
            String v = value.trim().toUpperCase(Locale.ROOT);
            if (v.equals("ARTIST") || v.equals("ARTIST_NOTE") || v.equals("NOTE") || v.equals("MUSIC")
                    || v.equals("АРТИСТ") || v.equals("НОТКА")) {
                return ARTIST_NOTE;
            }
            if (v.equals("CHECKMARK") || v.equals("CHECKMARK_100K") || v.equals("CHECK")
                    || v.equals("100K") || v.equals("VERIFIED") || v.equals("ГАЛОЧКА")) {
                return CHECKMARK_100K;
            }
            return NONE;
        }
    }
    private static final String MARKER_CHECKMARK = "\u2060\u200D\u00A0\u200D\u2060";
    private static final String MARKER_ARTIST = "\u2060\u200C\u00A0\u200C\u2060";
    private static final Pattern HANDLE_PREFIX_PATTERN =
            Pattern.compile("^(@[A-Za-z0-9_.-]{2,45})(?=\\s|$|[·•,])");
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2}:\\d{2}(?::\\d{2})?)(?!\\d)");
    private static final Pattern OG_IMAGE_PATTERN =
            Pattern.compile("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE_PATTERN =
            Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OEMBED_AUTHOR_URL_PATTERN =
            Pattern.compile("\"author_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OEMBED_AUTHOR_NAME_PATTERN =
            Pattern.compile("\"author_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final int[] AVATAR_PALETTE = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFF66BB6A, 0xFFFF7043, 0xFF8D6E63
    };
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private static final Map<String, String> channelAliases = new ConcurrentHashMap<>();
    private static volatile String learnedOwnHandle = "";
    private static volatile String learnedOwnTitle = "";
    private static volatile String lastChannelPageTitle = "";
    private static volatile String lastChannelPageHandle = "";
    private static final Map<String, VideoAuthorInfo> videoAuthors = new ConcurrentHashMap<>();
    private static final Set<String> pendingVideoAuthorFetches = ConcurrentHashMap.newKeySet();
    private static final LruCache<String, Bitmap> avatarMemoryCache = new LruCache<>(80);
    private static final Set<String> pendingAvatarFetches = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> handleAvatarUrls = new ConcurrentHashMap<>();
    private static final Set<RecyclerView> activeCommentRecyclerViews =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<RecyclerView, StageCommentsOverlayController> attachedDecorations =
            new WeakHashMap<>();
    private static final Map<ComponentHost, Boolean> attachedHostListeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, RealCommentInfo> visibleRealComments =
            Collections.synchronizedMap(new LinkedHashMap<>());
    @Nullable
    private static volatile List<StageComment> cachedStageComments;
    private static volatile String cachedStageCommentsRawJson = null;
    static {
        try {
            VideoInformation.onVideoIdChange.addObserver(StageCommentsAndVerificationPatch::onVideoIdLoaded);
        } catch (Exception ex) {
            Logger.printException(() -> "StageCommentsAndVerificationPatch init failure", ex);
        }
    }
    private StageCommentsAndVerificationPatch() {
    }
    public static boolean isPatchIncluded() {
        return false;
    }
    public record VideoAuthorInfo(String handle, String name, @Nullable String avatarUrl) {
    }
    public record RealCommentInfo(String key, String authorHandle, String bodySnippet) {
    }
    public static final class StageComment {
        public String id;
        public String videoId;
        public String authorHandle;
        public String authorName;
        public String badgeType;
        public String text;
        public String likeCount;
        public String timeAgo;
        public boolean hasCreatorHeart;
        public boolean isPinned;
        public boolean isLikedByMe;
        public boolean repliesExpanded = true;
        @Nullable
        public String targetRealCommentAuthor;
        @Nullable
        public String targetRealCommentSnippet;
        public final List<StageComment> replies = new ArrayList<>();
        public StageComment() {
            this.id = UUID.randomUUID().toString();
            this.videoId = "";
            this.authorHandle = "@User";
            this.authorName = "";
            this.badgeType = "AUTO";
            this.text = "";
            this.likeCount = "";
            this.timeAgo = "";
        }
        public boolean isReplyToRealComment() {
            return targetRealCommentAuthor != null && !targetRealCommentAuthor.isEmpty();
        }
        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id != null ? id : UUID.randomUUID().toString());
                obj.put("videoId", videoId != null ? videoId : "");
                obj.put("authorHandle", authorHandle != null ? authorHandle : "");
                obj.put("authorName", authorName != null ? authorName : "");
                obj.put("badgeType", badgeType != null ? badgeType : "AUTO");
                obj.put("text", text != null ? text : "");
                obj.put("likeCount", likeCount != null ? likeCount : "");
                obj.put("timeAgo", timeAgo != null ? timeAgo : "");
                obj.put("hasCreatorHeart", hasCreatorHeart);
                obj.put("isPinned", isPinned);
                obj.put("isLikedByMe", isLikedByMe);
                obj.put("repliesExpanded", repliesExpanded);
                if (targetRealCommentAuthor != null) {
                    obj.put("targetRealCommentAuthor", targetRealCommentAuthor);
                }
                if (targetRealCommentSnippet != null) {
                    obj.put("targetRealCommentSnippet", targetRealCommentSnippet);
                }
                JSONArray arr = new JSONArray();
                for (StageComment reply : replies) {
                    arr.put(reply.toJson());
                }
                obj.put("replies", arr);
            } catch (Exception ignored) {
            }
            return obj;
        }
        public static StageComment fromJson(JSONObject obj) {
            StageComment c = new StageComment();
            c.id = obj.optString("id", UUID.randomUUID().toString());
            c.videoId = obj.optString("videoId", "");
            c.authorHandle = obj.optString("authorHandle", "@User");
            c.authorName = obj.optString("authorName", "");
            c.badgeType = obj.optString("badgeType", "AUTO");
            c.text = obj.optString("text", "");
            c.likeCount = obj.optString("likeCount", "");
            c.timeAgo = obj.optString("timeAgo", "");
            c.hasCreatorHeart = obj.optBoolean("hasCreatorHeart", false);
            c.isPinned = obj.optBoolean("isPinned", false);
            c.isLikedByMe = obj.optBoolean("isLikedByMe", false);
            c.repliesExpanded = obj.optBoolean("repliesExpanded", true);
            String targetAuthor = obj.optString("targetRealCommentAuthor", "");
            c.targetRealCommentAuthor = targetAuthor.isEmpty() ? null : targetAuthor;
            String targetSnippet = obj.optString("targetRealCommentSnippet", "");
            c.targetRealCommentSnippet = targetSnippet.isEmpty() ? null : targetSnippet;
            JSONArray arr = obj.optJSONArray("replies");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rObj = arr.optJSONObject(i);
                    if (rObj != null) {
                        c.replies.add(fromJson(rObj));
                    }
                }
            }
            return c;
        }
    }
    private record CommentComposerMarkerSpan() {
    }
    private record CommentAuthorMarkerSpan(String handle) {
    }
    private record CommentBodyMarkerSpan(String bodyText) {
    }
    private record ChannelHeaderMarkerSpan(String channelName) {
    }
    public static void onVideoIdLoaded(@Nullable String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        visibleRealComments.clear();
        ensureVideoAuthorFetched(videoId);
        mainHandler.post(StageCommentsAndVerificationPatch::refreshAllCommentRecyclerViews);
    }
    private static void ensureVideoAuthorFetched(@Nullable String videoId) {
        if (videoId == null || videoId.isEmpty() || videoAuthors.containsKey(videoId)
                || !pendingVideoAuthorFetches.add(videoId)) {
            return;
        }
        networkExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v="
                        + videoId + "&format=json");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile");
                if (conn.getResponseCode() == 200) {
                    String json = readStreamToString(conn.getInputStream());
                    Matcher nameM = OEMBED_AUTHOR_NAME_PATTERN.matcher(json);
                    Matcher urlM = OEMBED_AUTHOR_URL_PATTERN.matcher(json);
                    String authorName = nameM.find() ? unescapeJsonString(nameM.group(1)) : "";
                    String authorUrl = urlM.find() ? unescapeJsonString(urlM.group(1)) : "";
                    String handle = "";
                    int atIdx = authorUrl.indexOf('@');
                    if (atIdx >= 0) {
                        handle = authorUrl.substring(atIdx);
                        int slashIdx = handle.indexOf('/');
                        if (slashIdx > 0) handle = handle.substring(0, slashIdx);
                    }
                    if (!handle.isEmpty() && !authorName.isEmpty()) {
                        linkChannelAlias(handle, authorName);
                    }
                    VideoAuthorInfo info = new VideoAuthorInfo(
                            !handle.isEmpty() ? handle : (!authorName.isEmpty() ? "@" + authorName.replace(" ", "") : "@Author"),
                            authorName,
                            null
                    );
                    videoAuthors.put(videoId, info);
                    if (!info.handle().isEmpty()) {
                        ensureAvatarFetched(info.handle());
                    }
                    mainHandler.post(StageCommentsAndVerificationPatch::refreshAllCommentRecyclerViews);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
                pendingVideoAuthorFetches.remove(videoId);
            }
        });
    }
    private static String normalizeChannelKey(@Nullable String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        while (trimmed.endsWith("\u2060") || trimmed.endsWith("\u200D")
                || trimmed.endsWith("\u200C") || trimmed.endsWith("\u00A0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
    public static void linkChannelAlias(@Nullable String handle, @Nullable String title) {
        String h = normalizeChannelKey(handle);
        String t = normalizeChannelKey(title);
        if (h.isEmpty() || t.isEmpty() || h.equals(t)) return;
        channelAliases.put(h, t);
        channelAliases.put(t, h);
    }
    public static Map<String, BadgeType> getConfiguredVerifiedChannels() {
        Map<String, BadgeType> map = new LinkedHashMap<>();
        String rawList = Settings.STAGE_VERIFIED_CHANNELS_LIST.get();
        if (rawList != null && !rawList.isEmpty()) {
            for (String line : rawList.split("[\\r\\n]+")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String channelPart = trimmed;
                BadgeType type = BadgeType.CHECKMARK_100K;
                int sep = trimmed.lastIndexOf('=');
                if (sep < 0) sep = trimmed.lastIndexOf('|');
                if (sep > 0) {
                    channelPart = trimmed.substring(0, sep).trim();
                    BadgeType parsed = BadgeType.fromString(trimmed.substring(sep + 1));
                    if (parsed != BadgeType.NONE) {
                        type = parsed;
                    }
                }
                String key = normalizeChannelKey(channelPart);
                if (!key.isEmpty()) {
                    map.put(key, type);
                    if (!key.startsWith("@") && !key.contains(" ")) {
                        map.put("@" + key, type);
                    } else if (key.startsWith("@") && key.length() > 1) {
                        map.put(key.substring(1), type);
                    }
                }
            }
        }
        return map;
    }
    public static void setChannelVerification(String channelHandleOrName, BadgeType badgeType) {
        if (channelHandleOrName == null || channelHandleOrName.trim().isEmpty()) return;
        String cleanTarget = channelHandleOrName.trim();
        String normTarget = normalizeChannelKey(cleanTarget);
        String normWithoutAt = normTarget.startsWith("@") ? normTarget.substring(1) : normTarget;
        String normWithAt = normTarget.startsWith("@") ? normTarget : "@" + normTarget;
        String alias = channelAliases.get(normTarget);
        String rawList = Settings.STAGE_VERIFIED_CHANNELS_LIST.get();
        List<String> keptLines = new ArrayList<>();
        if (rawList != null && !rawList.isEmpty()) {
            for (String line : rawList.split("[\\r\\n]+")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String part = trimmed;
                int sep = trimmed.lastIndexOf('=');
                if (sep < 0) sep = trimmed.lastIndexOf('|');
                if (sep > 0) part = trimmed.substring(0, sep).trim();
                String normLine = normalizeChannelKey(part);
                if (normLine.equals(normTarget) || normLine.equals(normWithoutAt)
                        || normLine.equals(normWithAt) || (alias != null && normLine.equals(alias))) {
                    continue;
                }
                keptLines.add(trimmed);
            }
        }
        if (badgeType != null && badgeType != BadgeType.NONE) {
            String suffix = badgeType == BadgeType.ARTIST_NOTE ? "artist" : "check";
            keptLines.add(cleanTarget + "=" + suffix);
        }
        Settings.STAGE_VERIFIED_CHANNELS_LIST.save(String.join("\n", keptLines));
        triggerVerificationRelayout();
    }
    public static void resetAllChannelVerifications() {
        Settings.STAGE_VERIFIED_CHANNELS_LIST.resetToDefault();
        Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.resetToDefault();
        Settings.STAGE_OWN_CHANNEL_HANDLES.resetToDefault();
        triggerVerificationRelayout();
    }
    public static void triggerVerificationRelayout() {
        mainHandler.post(() -> {
            try {
                LithoRelayoutPatch.remountListViews();
                LithoRelayoutPatch.relayoutOutdatedTexts();
                refreshAllCommentRecyclerViews();
            } catch (Exception ignored) {
            }
        });
    }
    public static BadgeType getBadgeForChannel(@Nullable String nameOrHandle) {
        if (!Settings.STAGE_VERIFICATION_ENABLED.get() || nameOrHandle == null) {
            return BadgeType.NONE;
        }
        String key = normalizeChannelKey(nameOrHandle);
        if (key.isEmpty()) return BadgeType.NONE;
        BadgeType ownBadge = BadgeType.fromString(Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.get());
        if (ownBadge != BadgeType.NONE && isOwnChannel(key)) {
            return ownBadge;
        }
        Map<String, BadgeType> map = getConfiguredVerifiedChannels();
        BadgeType direct = map.get(key);
        if (direct != null) return direct;
        if (key.startsWith("@") && key.length() > 1) {
            BadgeType withoutAt = map.get(key.substring(1));
            if (withoutAt != null) return withoutAt;
        } else {
            BadgeType withAt = map.get("@" + key);
            if (withAt != null) return withAt;
        }
        String alias = channelAliases.get(key);
        if (alias != null) {
            BadgeType aliasBadge = map.get(alias);
            if (aliasBadge != null) return aliasBadge;
        }
        return BadgeType.NONE;
    }
    private static boolean isOwnChannel(String normalizedKey) {
        if (normalizedKey.isEmpty()) return false;
        if (!learnedOwnHandle.isEmpty() && (normalizedKey.equals(learnedOwnHandle)
                || normalizedKey.equals(learnedOwnHandle.startsWith("@") ? learnedOwnHandle.substring(1) : "@" + learnedOwnHandle))) {
            return true;
        }
        if (!learnedOwnTitle.isEmpty() && normalizedKey.equals(learnedOwnTitle)) {
            return true;
        }
        String rawOwn = Settings.STAGE_OWN_CHANNEL_HANDLES.get();
        if (rawOwn == null || rawOwn.trim().isEmpty()) return false;
        for (String token : rawOwn.split("[,\\r\\n]+")) {
            String k = normalizeChannelKey(token);
            if (k.isEmpty()) continue;
            if (normalizedKey.equals(k)) return true;
            if (k.startsWith("@") && normalizedKey.equals(k.substring(1))) return true;
            if (!k.startsWith("@") && normalizedKey.equals("@" + k)) return true;
            String alias = channelAliases.get(k);
            if (alias != null && normalizedKey.equals(alias)) return true;
        }
        return false;
    }
    public static String getPrimaryOwnHandle() {
        String rawOwn = Settings.STAGE_OWN_CHANNEL_HANDLES.get();
        if (rawOwn != null && !rawOwn.trim().isEmpty()) {
            for (String token : rawOwn.split("[,\\r\\n]+")) {
                String t = token.trim();
                if (t.startsWith("@")) return t;
            }
            String first = rawOwn.split("[,\\r\\n]+")[0].trim();
            if (!first.isEmpty()) return first.startsWith("@") ? first : "@" + first.replace(" ", "");
        }
        if (!learnedOwnHandle.isEmpty()) return learnedOwnHandle;
        return "@MyChannel";
    }
    private static boolean containsAscii(byte[] bytes, String target) {
        byte[] needle = target.getBytes(StandardCharsets.US_ASCII);
        if (bytes == null || needle.length == 0 || bytes.length < needle.length) return false;
        byte first = needle[0];
        int max = bytes.length - needle.length;
        for (int i = 0; i <= max; i++) {
            if (bytes[i] != first) continue;
            boolean match = true;
            for (int j = 1; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }
    public static byte[] onElementLoaded(byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0) return bytes;
            final boolean isPageHeader = containsAscii(bytes, "page_header.e");
            final boolean isChannelBar = !isPageHeader && (containsAscii(bytes, "slim_owner.e")
                    || containsAscii(bytes, "compact_channel_bar")
                    || containsAscii(bytes, "video_description_header"));
            if (!isPageHeader && !isChannelBar) {
                return bytes;
            }
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) return bytes;
            List<ProtoNode> textNodes = ProtoNode.textNodes(root);
            if (isPageHeader) {
                if (inspectChannelPageHeader(textNodes)) {
                    return ProtoNode.write(root);
                }
            } else {
                inspectVideoChannelBar(textNodes);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "StageCommentsAndVerificationPatch.onElementLoaded failure", ex);
        }
        return bytes;
    }
    private static boolean inspectChannelPageHeader(List<ProtoNode> textNodes) {
        String foundHandle = null;
        String foundTitle = null;
        ProtoNode titleNode = null;
        for (ProtoNode node : textNodes) {
            String t = node.getText().trim();
            if (t.startsWith("@") && t.length() >= 3 && t.length() <= 45 && !t.contains(" ")) {
                foundHandle = t;
                break;
            }
        }
        for (ProtoNode node : textNodes) {
            String t = node.getText().trim();
            if (t.isEmpty() || t.length() > 60) continue;
            if (t.startsWith("@") || t.startsWith("http") || t.contains(".eml") || t.contains("|")
                    || t.contains("_") || t.contains("/") || t.startsWith("UC")) {
                continue;
            }
            foundTitle = t;
            titleNode = node;
            break;
        }
        if (foundHandle != null) {
            lastChannelPageHandle = foundHandle;
        }
        if (foundTitle != null) {
            lastChannelPageTitle = foundTitle;
        }
        if (foundHandle != null && foundTitle != null) {
            linkChannelAlias(foundHandle, foundTitle);
        }
        if (NavigationButton.getSelectedNavigationButton() == NavigationButton.LIBRARY) {
            if (foundHandle != null) {
                learnedOwnHandle = normalizeChannelKey(foundHandle);
            }
            if (foundTitle != null) {
                learnedOwnTitle = normalizeChannelKey(foundTitle);
            }
        }
        boolean modified = false;
        if (titleNode != null && (getBadgeForChannel(foundTitle) != BadgeType.NONE
                || (foundHandle != null && getBadgeForChannel(foundHandle) != BadgeType.NONE))) {
            ProtoNode parent = titleNode.getParent();
            if (parent != null && parent.children != null) {
                for (ProtoNode sibling : new ArrayList<>(parent.children)) {
                    if (sibling.getFieldNumber() == 8) {
                        sibling.remove();
                        modified = true;
                    }
                }
                if (modified) {
                    titleNode.setText(foundTitle);
                }
            }
        }
        return modified;
    }
    private static void inspectVideoChannelBar(List<ProtoNode> textNodes) {
        String videoId = VideoInformation.getVideoId();
        for (ProtoNode node : textNodes) {
            String t = node.getText().trim();
            if (t.startsWith("https://yt3.ggpht.com/") || t.startsWith("https://yt3.googleusercontent.com/")) {
                if (videoId != null && !videoId.isEmpty()) {
                    VideoAuthorInfo existing = videoAuthors.get(videoId);
                    if (existing != null && existing.avatarUrl() == null) {
                        videoAuthors.put(videoId, new VideoAuthorInfo(existing.handle(), existing.name(), t));
                        handleAvatarUrls.put(normalizeChannelKey(existing.handle()), t);
                        ensureAvatarFetched(existing.handle());
                    }
                }
            }
        }
    }
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence original) {
        try {
            if (original == null || original.length() == 0) {
                return original;
            }
            StringBuilder pathBuilder = contextInterface.patch_getPathBuilder();
            String path = pathBuilder != null ? pathBuilder.toString() : "";
            if (path.contains("comment_composer.e") || path.contains("comments_entry_point")) {
                SpannableString spanned = toSpannable(original);
                spanned.setSpan(new CommentComposerMarkerSpan(), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                return spanned;
            }
            String rawStr = original.toString();
            if (path.contains("|comment.e") || path.contains("comment_thread")) {
                return processCommentLithoText(original, rawStr);
            }
            if (Settings.STAGE_VERIFICATION_ENABLED.get()) {
                if (path.contains("page_header.e")
                        || path.contains("slim_owner")
                        || path.contains("compact_channel_bar")
                        || path.contains("video_description_header")
                        || path.contains("channel_profile")) {
                    return processChannelHeaderOrOwnerText(original, rawStr, path.contains("page_header.e"));
                }
                if (path.contains("lockup.e") || path.contains("video_lockup_with_attachment.e")) {
                    return processFeedLockupText(original, rawStr);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "StageCommentsAndVerificationPatch.onLithoTextLoaded failure", ex);
        }
        return original;
    }
    private static CharSequence processCommentLithoText(CharSequence original, String rawStr) {
        String trimmed = rawStr.trim();
        Matcher handleMatcher = HANDLE_PREFIX_PATTERN.matcher(trimmed);
        if (handleMatcher.find()) {
            String handle = handleMatcher.group(1);
            BadgeType badge = getBadgeForChannel(handle);
            CharSequence result = original;
            if (badge != BadgeType.NONE && !hasVerificationMarker(rawStr)) {
                int handleEndInRaw = rawStr.indexOf(handle) + handle.length();
                if (handleEndInRaw >= handle.length() && handleEndInRaw <= rawStr.length()) {
                    result = injectVerificationMarker(original, handleEndInRaw, badge);
                }
            }
            SpannableString spanned = toSpannable(result);
            spanned.setSpan(new CommentAuthorMarkerSpan(handle), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            applyVerificationSpansIfPresent(spanned);
            return spanned;
        }
        if (trimmed.length() >= 2 && !trimmed.matches("^[0-9.,\\sKMBтысмлн]+$")) {
            SpannableString spanned = toSpannable(original);
            spanned.setSpan(new CommentBodyMarkerSpan(trimmed), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return spanned;
        }
        return original;
    }
    private static CharSequence processChannelHeaderOrOwnerText(CharSequence original, String rawStr, boolean isPageHeader) {
        String trimmed = rawStr.trim();
        if (trimmed.isEmpty() || trimmed.length() > 75 || hasVerificationMarker(rawStr)) {
            if (hasVerificationMarker(rawStr)) {
                SpannableString s = toSpannable(original);
                applyVerificationSpansIfPresent(s);
                return s;
            }
            return original;
        }
        if (trimmed.startsWith("@") && isPageHeader) {
            SpannableString s = toSpannable(original);
            s.setSpan(new ChannelHeaderMarkerSpan(trimmed), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return s;
        }
        BadgeType badge = getBadgeForChannel(trimmed);
        if (badge == BadgeType.NONE && isPageHeader
                && NavigationButton.getSelectedNavigationButton() == NavigationButton.LIBRARY
                && !trimmed.contains("•") && !trimmed.contains("·")) {
            BadgeType ownBadge = BadgeType.fromString(Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.get());
            if (ownBadge != BadgeType.NONE && trimmed.equals(lastChannelPageTitle)) {
                badge = ownBadge;
            }
        }
        if (badge != BadgeType.NONE) {
            CharSequence withMarker = injectVerificationMarker(original, original.length(), badge);
            SpannableString spanned = toSpannable(withMarker);
            spanned.setSpan(new ChannelHeaderMarkerSpan(trimmed), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            applyVerificationSpansIfPresent(spanned);
            return spanned;
        }
        if (isPageHeader && (trimmed.equals(lastChannelPageTitle) || trimmed.startsWith("@"))) {
            SpannableString spanned = toSpannable(original);
            spanned.setSpan(new ChannelHeaderMarkerSpan(trimmed), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return spanned;
        }
        return original;
    }
    private static CharSequence processFeedLockupText(CharSequence original, String rawStr) {
        if (hasVerificationMarker(rawStr) || rawStr.length() > 140) {
            return original;
        }
        int dotIdx = rawStr.indexOf(" · ");
        if (dotIdx < 0) dotIdx = rawStr.indexOf(" • ");
        String candidateChannel = (dotIdx > 0 ? rawStr.substring(0, dotIdx) : rawStr).trim();
        if (candidateChannel.isEmpty() || candidateChannel.length() > 50) {
            return original;
        }
        BadgeType badge = getBadgeForChannel(candidateChannel);
        if (badge == BadgeType.NONE) {
            return original;
        }
        int insertAt = dotIdx > 0 ? dotIdx : original.length();
        CharSequence withMarker = injectVerificationMarker(original, insertAt, badge);
        SpannableString spanned = toSpannable(withMarker);
        applyVerificationSpansIfPresent(spanned);
        return spanned;
    }
    private static boolean hasVerificationMarker(String s) {
        return s.contains(MARKER_CHECKMARK) || s.contains(MARKER_ARTIST);
    }
    private static CharSequence injectVerificationMarker(CharSequence original, int insertIndex, BadgeType badge) {
        String marker = badge == BadgeType.ARTIST_NOTE ? MARKER_ARTIST : MARKER_CHECKMARK;
        SpannableStringBuilder sb = new SpannableStringBuilder(original);
        sb.insert(Math.max(0, Math.min(insertIndex, sb.length())), marker);
        return new SpannableString(sb);
    }
    private static SpannableString toSpannable(CharSequence cs) {
        return cs instanceof SpannableString s ? s : new SpannableString(cs);
    }
    public static void applyVerificationSpansIfPresent(Spannable spannable) {
        String text = spannable.toString();
        applyMarkerSpan(spannable, text, MARKER_CHECKMARK, BadgeType.CHECKMARK_100K);
        applyMarkerSpan(spannable, text, MARKER_ARTIST, BadgeType.ARTIST_NOTE);
    }
    private static void applyMarkerSpan(Spannable spannable, String text, String marker, BadgeType type) {
        int idx = text.indexOf(marker);
        while (idx >= 0) {
            int end = idx + marker.length();
            VerificationBadgeSpan[] existing = spannable.getSpans(idx, end, VerificationBadgeSpan.class);
            if (existing == null || existing.length == 0) {
                spannable.setSpan(new VerificationBadgeSpan(type), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            idx = text.indexOf(marker, end);
        }
    }
    public static final class VerificationSpanFilter extends SpanFilter {
        public VerificationSpanFilter() {
            addCallbacks(
                    new StringSpanFilterGroup(
                            Settings.STAGE_VERIFICATION_ENABLED,
                            "page_header.e",
                            "slim_owner",
                            "compact_channel_bar",
                            "video_description_header",
                            "channel_profile",
                            "|comment.e",
                            "comment_thread",
                            "lockup.e",
                            "video_lockup_with_attachment.e"
                    )
            );
        }

        @Override
        public boolean skip(String identifier, CharSequence path, SpannableString spannableString,
                            Object span, int start, int end, int flags, boolean isWord,
                            SpanType spanType, StringSpanFilterGroup matchedGroup) {
            String raw = spannableString.toString();
            if (!hasVerificationMarker(raw)) {
                return false;
            }
            spannableString.setSpan(span, start, end, flags);
            applyVerificationSpansIfPresent(spannableString);
            return true;
        }
    }
    public static final class VerificationBadgeSpan extends ReplacementSpan {
        private final BadgeType badgeType;
        public VerificationBadgeSpan(BadgeType badgeType) {
            this.badgeType = badgeType;
        }
        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fm) {
            float textSize = paint.getTextSize();
            int iconSize = Math.max(Dim.dp(13), Math.round(textSize * 0.82f));
            int marginStart = Math.max(Dim.dp(4), Math.round(textSize * 0.22f));
            int marginEnd = Math.max(Dim.dp(2), Math.round(textSize * 0.08f));
            return marginStart + iconSize + marginEnd;
        }
        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            float textSize = paint.getTextSize();
            int iconSize = Math.max(Dim.dp(13), Math.round(textSize * 0.82f));
            int marginStart = Math.max(Dim.dp(4), Math.round(textSize * 0.22f));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float textCenterY = y + (fm.ascent + fm.descent) * 0.5f;
            float left = x + marginStart;
            float iconTop = textCenterY - iconSize * 0.5f;
            boolean dark = isDarkTheme();
            int badgeColor = dark ? 0xFFAAAAAA : 0xFF606060;
            drawVerificationVector(canvas, left, iconTop, iconSize, badgeType, badgeColor);
        }
    }
    public static final class VerificationBadgeDrawable extends Drawable {
        private final BadgeType badgeType;
        private final int sizePx;
        public VerificationBadgeDrawable(BadgeType badgeType, int sizePx) {
            this.badgeType = badgeType;
            this.sizePx = sizePx;
            setBounds(0, 0, sizePx, sizePx);
        }
        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }
        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            boolean dark = isDarkTheme();
            int color = dark ? 0xFFAAAAAA : 0xFF606060;
            drawVerificationVector(canvas, b.left, b.top, Math.min(b.width(), b.height()), badgeType, color);
        }
        @Override
        public void setAlpha(int alpha) {
        }
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    public static void drawVerificationVector(Canvas canvas, float left, float top, float size,
                                              BadgeType badgeType, int color) {
        if (badgeType == null || badgeType == BadgeType.NONE || size <= 0) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        float scale = size / 24f;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        if (badgeType == BadgeType.ARTIST_NOTE) {
            Path note = new Path();
            note.moveTo(12f, 3f);
            note.lineTo(12f, 13.55f);
            note.cubicTo(11.41f, 13.21f, 10.73f, 13f, 10f, 13f);
            note.cubicTo(7.79f, 13f, 6f, 14.79f, 6f, 17f);
            note.cubicTo(6f, 19.21f, 7.79f, 21f, 10f, 21f);
            note.cubicTo(12.21f, 21f, 14f, 19.21f, 14f, 17f);
            note.lineTo(14f, 7f);
            note.lineTo(18f, 7f);
            note.lineTo(18f, 3f);
            note.close();
            canvas.drawPath(note, p);
        } else {
            Path circle = new Path();
            circle.addCircle(12f, 12f, 10f, Path.Direction.CW);
            Path check = new Path();
            check.moveTo(9.5f, 16.2f);
            check.lineTo(5.8f, 12.5f);
            check.lineTo(7.2f, 11.1f);
            check.lineTo(9.5f, 13.4f);
            check.lineTo(16.8f, 6.1f);
            check.lineTo(18.2f, 7.5f);
            check.close();
            if (circle.op(check, Path.Op.DIFFERENCE)) {
                canvas.drawPath(circle, p);
            } else {
                canvas.drawCircle(12f, 12f, 10f, p);
                Paint cutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                cutPaint.setStyle(Paint.Style.FILL);
                cutPaint.setColor(isDarkTheme() ? 0xFF0F0F0F : 0xFFFFFFFF);
                canvas.drawPath(check, cutPaint);
            }
        }
        canvas.restore();
    }
    public static void onComponentHostLayout(ComponentHost host) {
        if (host == null) return;
        try {
            ComponentHost rootHost = findRootComponentHost(host);
            boolean isComposer = false;
            String commentAuthor = null;
            String commentBody = null;
            String channelHeaderName = null;
            List<ComponentHost> hosts = new ArrayList<>();
            collectComponentHosts(rootHost, hosts);
            for (ComponentHost h : hosts) {
                TextContent tc = h.getTextContent();
                if (tc == null) continue;
                for (CharSequence item : tc.getTextItems()) {
                    if (!(item instanceof Spanned spanned)) continue;
                    int len = spanned.length();
                    if (spanned.getSpans(0, len, CommentComposerMarkerSpan.class).length > 0) {
                        isComposer = true;
                    }
                    CommentAuthorMarkerSpan[] aSpans = spanned.getSpans(0, len, CommentAuthorMarkerSpan.class);
                    if (aSpans.length > 0 && commentAuthor == null) {
                        commentAuthor = aSpans[0].handle();
                    }
                    CommentBodyMarkerSpan[] bSpans = spanned.getSpans(0, len, CommentBodyMarkerSpan.class);
                    if (bSpans.length > 0 && commentBody == null) {
                        commentBody = bSpans[0].bodyText();
                    }
                    ChannelHeaderMarkerSpan[] chSpans = spanned.getSpans(0, len, ChannelHeaderMarkerSpan.class);
                    if (chSpans.length > 0 && channelHeaderName == null) {
                        channelHeaderName = chSpans[0].channelName();
                    }
                }
            }
            if (isComposer || commentAuthor != null) {
                if (commentAuthor != null) {
                    String snippet = commentBody != null ? commentBody : "";
                    String key = buildRealCommentKey(commentAuthor, snippet);
                    rootHost.setTag(0x7F0B5A01, new RealCommentInfo(key, commentAuthor, snippet));
                    visibleRealComments.put(key, new RealCommentInfo(key, commentAuthor, snippet));
                } else {
                    rootHost.setTag(0x7F0B5A02, Boolean.TRUE);
                }
                RecyclerView rv = findParentRecyclerView(rootHost);
                if (rv != null) {
                    attachCommentsRecyclerViewIfNeeded(rv);
                }
                attachLongPressHandlerIfNeeded(rootHost);
            } else if (channelHeaderName != null) {
                rootHost.setTag(0x7F0B5A03, channelHeaderName);
                attachLongPressHandlerIfNeeded(rootHost);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "StageCommentsAndVerificationPatch.onComponentHostLayout failure", ex);
        }
    }
    private static ComponentHost findRootComponentHost(ComponentHost host) {
        ComponentHost current = host;
        while (current.getParent() instanceof ComponentHost parentHost) {
            current = parentHost;
        }
        return current;
    }
    private static void collectComponentHosts(View view, List<ComponentHost> out) {
        if (view instanceof ComponentHost ch) {
            out.add(ch);
        }
        if (view instanceof ViewGroup vg) {
            for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                collectComponentHosts(vg.getChildAt(i), out);
            }
        }
    }
    @Nullable
    private static RecyclerView findParentRecyclerView(View view) {
        ViewParent p = view.getParent();
        int depth = 0;
        while (p instanceof View v && depth < 6) {
            if (v instanceof RecyclerView rv) {
                return rv;
            }
            p = v.getParent();
            depth++;
        }
        return null;
    }
    private static void attachLongPressHandlerIfNeeded(ComponentHost rootHost) {
        if (attachedHostListeners.containsKey(rootHost)) {
            return;
        }
        attachedHostListeners.put(rootHost, Boolean.TRUE);
        final int touchSlop = ViewConfiguration.get(rootHost.getContext()).getScaledTouchSlop();
        final long longPressTimeout = ViewConfiguration.getLongPressTimeout() + 120L;
        rootHost.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean longPressTriggered;
            private final Runnable longPressRunnable = () -> {
                if (!Settings.STAGE_COMMENTS_LONG_PRESS_HINT.get()) return;
                Activity activity = findActivity(rootHost.getContext());
                if (activity == null || activity.isFinishing()) return;
                longPressTriggered = true;
                try {
                    rootHost.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Exception ignored) {
                }
                Object realCommentTag = rootHost.getTag(0x7F0B5A01);
                Object composerTag = rootHost.getTag(0x7F0B5A02);
                Object channelHeaderTag = rootHost.getTag(0x7F0B5A03);
                if (realCommentTag instanceof RealCommentInfo info) {
                    showRealCommentActionDialog(activity, info);
                } else if (Boolean.TRUE.equals(composerTag)) {
                    showStageManagerDialog(activity);
                } else if (channelHeaderTag instanceof String chName) {
                    String target = !lastChannelPageHandle.isEmpty() ? lastChannelPageHandle : chName;
                    showQuickVerifyChannelDialog(activity, target);
                }
            };
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = event.getX();
                    downY = event.getY();
                    longPressTriggered = false;
                    mainHandler.removeCallbacks(longPressRunnable);
                    mainHandler.postDelayed(longPressRunnable, longPressTimeout);
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop) {
                        mainHandler.removeCallbacks(longPressRunnable);
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mainHandler.removeCallbacks(longPressRunnable);
                    if (longPressTriggered && action == MotionEvent.ACTION_UP) {
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        v.onTouchEvent(cancel);
                        cancel.recycle();
                        return true;
                    }
                }
                return false;
            }
        });
    }
    private static void attachCommentsRecyclerViewIfNeeded(RecyclerView rv) {
        activeCommentRecyclerViews.add(rv);
        if (attachedDecorations.containsKey(rv)) {
            return;
        }
        StageCommentsOverlayController controller = new StageCommentsOverlayController(rv);
        attachedDecorations.put(rv, controller);
        controller.attach(rv);
    }

    public static void refreshAllCommentRecyclerViews() {
        for (RecyclerView rv : new ArrayList<>(activeCommentRecyclerViews)) {
            if (rv != null && rv.isAttachedToWindow()) {
                StageCommentsOverlayController ctrl = attachedDecorations.get(rv);
                if (ctrl != null) {
                    ctrl.invalidateCache();
                    ctrl.syncMarginsAndRedraw(rv);
                }
                rv.requestLayout();
                rv.invalidate();
            }
        }
    }

    private static void invalidateAllCommentRecyclerViews() {
        for (RecyclerView rv : new ArrayList<>(activeCommentRecyclerViews)) {
            if (rv != null && rv.isAttachedToWindow()) {
                StageCommentsOverlayController ctrl = attachedDecorations.get(rv);
                if (ctrl != null) {
                    ctrl.syncMarginsAndRedraw(rv);
                }
                rv.invalidate();
            }
        }
    }

    public static synchronized List<StageComment> getAllStageComments() {
        String raw = Settings.STAGE_COMMENTS_DATA_JSON.get();
        if (raw == null) raw = "";
        if (cachedStageComments != null && raw.equals(cachedStageCommentsRawJson)) {
            return cachedStageComments;
        }
        List<StageComment> list = new ArrayList<>();
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) {
                        list.add(StageComment.fromJson(obj));
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse stage comments JSON", ex);
            }
        }
        cachedStageComments = list;
        cachedStageCommentsRawJson = raw;
        return list;
    }

    public static synchronized void saveAllStageComments(List<StageComment> comments) {
        try {
            JSONArray arr = new JSONArray();
            for (StageComment c : comments) {
                arr.put(c.toJson());
            }
            String json = arr.toString();
            cachedStageComments = new ArrayList<>(comments);
            cachedStageCommentsRawJson = json;
            Settings.STAGE_COMMENTS_DATA_JSON.save(json);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save stage comments JSON", ex);
        }
        mainHandler.post(StageCommentsAndVerificationPatch::refreshAllCommentRecyclerViews);
    }

    public static void resetAllStageComments() {
        synchronized (StageCommentsAndVerificationPatch.class) {
            cachedStageComments = new ArrayList<>();
            cachedStageCommentsRawJson = "";
            Settings.STAGE_COMMENTS_DATA_JSON.resetToDefault();
        }
        mainHandler.post(StageCommentsAndVerificationPatch::refreshAllCommentRecyclerViews);
    }

    public static List<StageComment> getStageTopCommentsForCurrentVideo() {
        if (!Settings.STAGE_COMMENTS_ENABLED.get()) return Collections.emptyList();
        String currentVideoId = VideoInformation.getVideoId();
        List<StageComment> result = new ArrayList<>();
        for (StageComment c : getAllStageComments()) {
            if (c.isReplyToRealComment()) continue;
            if ("*".equals(c.videoId) || (currentVideoId != null && currentVideoId.equals(c.videoId))) {
                result.add(c);
            }
        }
        return result;
    }

    public static List<StageComment> getStageRepliesForRealComment(String authorHandle, String bodySnippet) {
        if (!Settings.STAGE_COMMENTS_ENABLED.get()) return Collections.emptyList();
        String currentVideoId = VideoInformation.getVideoId();
        String normAuthor = normalizeChannelKey(authorHandle);
        String normSnippet = normalizeSnippet(bodySnippet);

        List<StageComment> result = new ArrayList<>();
        for (StageComment c : getAllStageComments()) {
            if (!c.isReplyToRealComment()) continue;
            if (!"*".equals(c.videoId) && (currentVideoId == null || !currentVideoId.equals(c.videoId))) {
                continue;
            }
            String targetAuthor = normalizeChannelKey(c.targetRealCommentAuthor);
            if (!targetAuthor.equals(normAuthor)) continue;

            String targetSnippet = normalizeSnippet(c.targetRealCommentSnippet);
            if (targetSnippet.isEmpty() || normSnippet.isEmpty()
                    || normSnippet.startsWith(targetSnippet) || targetSnippet.startsWith(normSnippet)) {
                result.add(c);
            }
        }
        return result;
    }

    private static String buildRealCommentKey(String authorHandle, String bodySnippet) {
        return normalizeChannelKey(authorHandle) + "|" + normalizeSnippet(bodySnippet);
    }

    private static String normalizeSnippet(@Nullable String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\s+", " ");
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private static final class HitTarget {
        final Rect rectInRv;
        final Runnable onClick;

        HitTarget(Rect rectInRv, Runnable onClick) {
            this.rectInRv = rectInRv;
            this.onClick = onClick;
        }
    }

    private static final class StageCommentsOverlayController extends Drawable {
        private final WeakReference<RecyclerView> rvRef;
        private final List<HitTarget> activeHitTargets = new ArrayList<>();
        @Nullable
        private LinearLayout cachedTopBlock;
        private int cachedTopBlockWidth = -1;
        private final Map<String, LinearLayout> cachedRealReplyBlocks = new LinkedHashMap<>();

        StageCommentsOverlayController(RecyclerView rv) {
            this.rvRef = new WeakReference<>(rv);
        }

        void attach(RecyclerView rv) {
            setBounds(0, 0, Math.max(1, rv.getWidth()), Math.max(1, rv.getHeight()));
            rv.getOverlay().add(this);

            rv.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                setBounds(0, 0, Math.max(1, right - left), Math.max(1, bottom - top));
                syncMarginsAndRedraw(rv);
            });

            rv.getViewTreeObserver().addOnDrawListener(() -> {
                if (rv.isAttachedToWindow()) {
                    setBounds(0, 0, Math.max(1, rv.getWidth()), Math.max(1, rv.getHeight()));
                    syncMarginsOnly(rv);
                }
            });

            final int slop = ViewConfiguration.get(rv.getContext()).getScaledTouchSlop();
            rv.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;
                @Nullable
                private HitTarget pendingTarget;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    int action = e.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        downX = e.getX();
                        downY = e.getY();
                        pendingTarget = findHitTarget((int) downX, (int) downY);
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                            pendingTarget = null;
                        }
                    } else if (action == MotionEvent.ACTION_UP) {
                        if (pendingTarget != null) {
                            HitTarget target = pendingTarget;
                            pendingTarget = null;
                            if (target.rectInRv.contains((int) e.getX(), (int) e.getY())) {
                                target.onClick.run();
                                return true;
                            }
                        }
                    } else if (action == MotionEvent.ACTION_CANCEL) {
                        pendingTarget = null;
                    }
                    return false;
                }
            });
        }

        void invalidateCache() {
            cachedTopBlock = null;
            cachedTopBlockWidth = -1;
            cachedRealReplyBlocks.clear();
        }

        void syncMarginsAndRedraw(RecyclerView rv) {
            syncMarginsOnly(rv);
            invalidateSelf();
        }

        private void syncMarginsOnly(RecyclerView parent) {
            boolean enabled = Settings.STAGE_COMMENTS_ENABLED.get();
            List<StageComment> topComments = enabled ? getStageTopCommentsForCurrentVideo() : Collections.emptyList();
            boolean changedAny = false;

            for (int i = 0, n = parent.getChildCount(); i < n; i++) {
                View child = parent.getChildAt(i);
                int desiredBottomMargin = 0;

                if (!topComments.isEmpty() && isAnchorForTopStageComments(parent, child)) {
                    LinearLayout topBlock = getOrBuildTopBlock(parent, topComments);
                    desiredBottomMargin += topBlock.getMeasuredHeight();
                }

                Object realTag = child.getTag(0x7F0B5A01);
                if (enabled && realTag instanceof RealCommentInfo info) {
                    List<StageComment> replies = getStageRepliesForRealComment(info.authorHandle(), info.bodySnippet());
                    if (!replies.isEmpty()) {
                        LinearLayout replyBlock = getOrBuildRealReplyBlock(parent, info.key(), replies);
                        desiredBottomMargin += replyBlock.getMeasuredHeight();
                    }
                }

                ViewGroup.LayoutParams rawLp = child.getLayoutParams();
                if (rawLp instanceof ViewGroup.MarginLayoutParams mlp) {
                    Integer origBottom = (Integer) child.getTag(0x7F0B5A09);
                    if (origBottom == null) {
                        origBottom = mlp.bottomMargin;
                        child.setTag(0x7F0B5A09, origBottom);
                    }
                    int targetBottom = origBottom + desiredBottomMargin;
                    if (mlp.bottomMargin != targetBottom) {
                        mlp.bottomMargin = targetBottom;
                        child.setLayoutParams(mlp);
                        changedAny = true;
                    }
                }
            }
            if (changedAny) {
                parent.post(parent::requestLayout);
            }
        }

        @Nullable
        private HitTarget findHitTarget(int x, int y) {
            for (int i = activeHitTargets.size() - 1; i >= 0; i--) {
                HitTarget t = activeHitTargets.get(i);
                if (t.rectInRv.contains(x, y)) {
                    return t;
                }
            }
            return null;
        }

        private LinearLayout getOrBuildTopBlock(RecyclerView parent, List<StageComment> topComments) {
            int width = parent.getWidth();
            if (width <= 0) {
                width = parent.getResources().getDisplayMetrics().widthPixels;
            }
            if (cachedTopBlock != null && cachedTopBlockWidth == width) {
                return cachedTopBlock;
            }
            Context ctx = parent.getContext();
            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setBackgroundColor(isDarkTheme() ? 0xFF0F0F0F : 0xFFFFFFFF);

            for (StageComment c : topComments) {
                container.addView(buildStageCommentView(ctx, c, false));
            }

            measureAndLayoutBlock(container, width);
            cachedTopBlock = container;
            cachedTopBlockWidth = width;
            return container;
        }

        private LinearLayout getOrBuildRealReplyBlock(RecyclerView parent, String key, List<StageComment> replies) {
            int width = parent.getWidth();
            if (width <= 0) {
                width = parent.getResources().getDisplayMetrics().widthPixels;
            }
            LinearLayout cached = cachedRealReplyBlocks.get(key);
            if (cached != null && cached.getMeasuredWidth() == width) {
                return cached;
            }
            Context ctx = parent.getContext();
            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setBackgroundColor(isDarkTheme() ? 0xFF0F0F0F : 0xFFFFFFFF);

            for (StageComment reply : replies) {
                container.addView(buildStageCommentView(ctx, reply, true));
            }

            measureAndLayoutBlock(container, width);
            cachedRealReplyBlocks.put(key, container);
            return container;
        }

        private static void measureAndLayoutBlock(LinearLayout block, int width) {
            int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            block.measure(wSpec, hSpec);
            block.layout(0, 0, block.getMeasuredWidth(), block.getMeasuredHeight());
        }

        private boolean isAnchorForTopStageComments(RecyclerView parent, View child) {
            if (Boolean.TRUE.equals(child.getTag(0x7F0B5A02))) {
                return true;
            }
            for (int i = 0, n = parent.getChildCount(); i < n; i++) {
                if (Boolean.TRUE.equals(parent.getChildAt(i).getTag(0x7F0B5A02))) {
                    return false;
                }
            }
            return parent.getChildCount() > 0 && parent.getChildAt(0) == child;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            activeHitTargets.clear();
            RecyclerView parent = rvRef.get();
            if (parent == null || !Settings.STAGE_COMMENTS_ENABLED.get()) return;

            List<StageComment> topComments = getStageTopCommentsForCurrentVideo();
            for (int i = 0, n = parent.getChildCount(); i < n; i++) {
                View child = parent.getChildAt(i);
                int currentDrawTop = child.getBottom();

                if (!topComments.isEmpty() && isAnchorForTopStageComments(parent, child)) {
                    LinearLayout topBlock = getOrBuildTopBlock(parent, topComments);
                    drawBlockAndCollectHits(canvas, topBlock, 0, currentDrawTop);
                    currentDrawTop += topBlock.getMeasuredHeight();
                }

                Object realTag = child.getTag(0x7F0B5A01);
                if (realTag instanceof RealCommentInfo info) {
                    List<StageComment> replies = getStageRepliesForRealComment(info.authorHandle(), info.bodySnippet());
                    if (!replies.isEmpty()) {
                        LinearLayout replyBlock = getOrBuildRealReplyBlock(parent, info.key(), replies);
                        drawBlockAndCollectHits(canvas, replyBlock, 0, currentDrawTop);
                    }
                }
            }
        }

        private void drawBlockAndCollectHits(Canvas canvas, LinearLayout block, int left, int top) {
            canvas.save();
            canvas.translate(left, top);
            block.draw(canvas);
            canvas.restore();
            collectHitTargetsRecursive(block, left, top);
        }

        private void collectHitTargetsRecursive(View view, int offsetX, int offsetY) {
            if (view.getVisibility() != View.VISIBLE) return;
            Object clickAction = view.getTag(0x7F0B5A10);
            if (clickAction instanceof Runnable action) {
                Rect r = new Rect(
                        offsetX + view.getLeft(),
                        offsetY + view.getTop(),
                        offsetX + view.getRight(),
                        offsetY + view.getBottom()
                );
                activeHitTargets.add(new HitTarget(r, action));
            }
            if (view instanceof ViewGroup vg) {
                int childOffsetX = offsetX + view.getLeft();
                int childOffsetY = offsetY + view.getTop();
                for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                    collectHitTargetsRecursive(vg.getChildAt(i), childOffsetX, childOffsetY);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static View buildStageCommentView(Context context, StageComment comment, boolean isReply) {
        final boolean dark = isDarkTheme();
        final int primaryTextColor = dark ? 0xFFF1F1F1 : 0xFF0F0F0F;
        final int secondaryTextColor = dark ? 0xFFAAAAAA : 0xFF606060;
        final int accentBlueColor = dark ? 0xFF3EA6FF : 0xFF065FD4;
        LinearLayout outerWrapper = new LinearLayout(context);
        outerWrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int startPad = isReply ? Dim.dp(52) : Dim.dp(16);
        int topPad = isReply ? Dim.dp(8) : Dim.dp(12);
        int bottomPad = isReply ? Dim.dp(6) : Dim.dp(8);
        row.setPadding(startPad, topPad, Dim.dp(8), bottomPad);
        int avatarSize = isReply ? Dim.dp(24) : Dim.dp(36);
        ImageView avatarView = new ImageView(context);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.topMargin = Dim.dp(2);
        avatarLp.setMarginEnd(Dim.dp(12));
        avatarView.setLayoutParams(avatarLp);
        avatarView.setImageDrawable(new CircularAvatarDrawable(comment.authorHandle, avatarSize));
        LinearLayout contentCol = new LinearLayout(context);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        contentCol.setLayoutParams(contentLp);
        if (!isReply && comment.isPinned) {
            LinearLayout pinRow = new LinearLayout(context);
            pinRow.setOrientation(LinearLayout.HORIZONTAL);
            pinRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams pinRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pinRowLp.bottomMargin = Dim.dp(3);
            pinRow.setLayoutParams(pinRowLp);
            ImageView pinIcon = new ImageView(context);
            LinearLayout.LayoutParams pinIconLp = new LinearLayout.LayoutParams(Dim.dp(12), Dim.dp(12));
            pinIconLp.setMarginEnd(Dim.dp(4));
            pinIcon.setLayoutParams(pinIconLp);
            pinIcon.setImageDrawable(new VectorPinDrawable(secondaryTextColor));
            pinRow.addView(pinIcon);
            String videoAuthorHandle = getCurrentVideoAuthorHandle();
            TextView pinText = new TextView(context);
            pinText.setText(str("morphe_stage_comments_pinned_by", videoAuthorHandle));
            pinText.setTextColor(secondaryTextColor);
            pinText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            pinRow.addView(pinText);
            contentCol.addView(pinRow);
        }
        LinearLayout authorRow = new LinearLayout(context);
        authorRow.setOrientation(LinearLayout.HORIZONTAL);
        authorRow.setGravity(Gravity.CENTER_VERTICAL);
        String displayHandle = formatAuthorHandle(comment.authorHandle);
        TextView authorTv = new TextView(context);
        authorTv.setText(displayHandle);
        authorTv.setTextColor(secondaryTextColor);
        authorTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        authorTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        authorRow.addView(authorTv);
        BadgeType resolvedBadge = resolveCommentBadge(comment);
        if (resolvedBadge != BadgeType.NONE) {
            ImageView badgeView = new ImageView(context);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(Dim.dp(13), Dim.dp(13));
            badgeLp.setMarginStart(Dim.dp(4));
            badgeView.setLayoutParams(badgeLp);
            badgeView.setImageDrawable(new VerificationBadgeDrawable(resolvedBadge, Dim.dp(13)));
            authorRow.addView(badgeView);
        }
        String timeText = (comment.timeAgo != null && !comment.timeAgo.trim().isEmpty())
                ? comment.timeAgo.trim()
                : str("morphe_stage_comments_default_time");
        TextView timeTv = new TextView(context);
        timeTv.setText(" · " + timeText);
        timeTv.setTextColor(secondaryTextColor);
        timeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        authorRow.addView(timeTv);
        contentCol.addView(authorRow);
        TextView bodyTv = new TextView(context);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Dim.dp(3);
        bodyTv.setLayoutParams(bodyLp);
        bodyTv.setTextColor(primaryTextColor);
        bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        bodyTv.setLineSpacing(Dim.dp(2), 1.0f);
        final Long firstTimestampMs = attachBodySpansAndFindFirstTimestamp(bodyTv, comment.text, accentBlueColor);
        if (firstTimestampMs != null) {
            final long seekTarget = firstTimestampMs;
            bodyTv.setTag(0x7F0B5A10, (Runnable) () -> VideoInformation.seekTo(seekTarget));
        }
        contentCol.addView(bodyTv);
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp(32));
        actionRowLp.topMargin = Dim.dp(2);
        actionRow.setLayoutParams(actionRowLp);
        LinearLayout likeGroup = new LinearLayout(context);
        likeGroup.setOrientation(LinearLayout.HORIZONTAL);
        likeGroup.setGravity(Gravity.CENTER_VERTICAL);
        likeGroup.setPadding(0, Dim.dp(4), Dim.dp(14), Dim.dp(4));
        ImageView likeIcon = new ImageView(context);
        likeIcon.setLayoutParams(new LinearLayout.LayoutParams(Dim.dp(16), Dim.dp(16)));
        likeIcon.setImageDrawable(getThumbIcon(true, comment.isLikedByMe, primaryTextColor));
        likeGroup.addView(likeIcon);
        String displayLikes = getDisplayLikeCount(comment);
        if (!displayLikes.isEmpty()) {
            TextView likeCountTv = new TextView(context);
            LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lcLp.setMarginStart(Dim.dp(6));
            likeCountTv.setLayoutParams(lcLp);
            likeCountTv.setText(displayLikes);
            likeCountTv.setTextColor(secondaryTextColor);
            likeCountTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            likeGroup.addView(likeCountTv);
        }
        likeGroup.setTag(0x7F0B5A10, (Runnable) () -> {
            comment.isLikedByMe = !comment.isLikedByMe;
            saveAllStageComments(getAllStageComments());
        });
        actionRow.addView(likeGroup);
        FrameLayout dislikeBtn = new FrameLayout(context);
        dislikeBtn.setPadding(Dim.dp(4), Dim.dp(4), Dim.dp(18), Dim.dp(4));
        ImageView dislikeIcon = new ImageView(context);
        dislikeIcon.setLayoutParams(new FrameLayout.LayoutParams(Dim.dp(16), Dim.dp(16), Gravity.CENTER));
        dislikeIcon.setImageDrawable(getThumbIcon(false, false, primaryTextColor));
        dislikeBtn.addView(dislikeIcon);
        actionRow.addView(dislikeBtn);
        FrameLayout replyBtn = new FrameLayout(context);
        replyBtn.setPadding(Dim.dp(4), Dim.dp(4), Dim.dp(18), Dim.dp(4));
        ImageView replyIcon = new ImageView(context);
        replyIcon.setLayoutParams(new FrameLayout.LayoutParams(Dim.dp(16), Dim.dp(16), Gravity.CENTER));
        replyIcon.setImageDrawable(getReplyIcon(primaryTextColor));
        replyBtn.addView(replyIcon);
        replyBtn.setTag(0x7F0B5A10, (Runnable) () -> {
            Activity act = findActivity(context);
            if (act != null) {
                showCreateOrEditStageCommentDialog(act, null, comment, null);
            }
        });
        actionRow.addView(replyBtn);
        if (comment.hasCreatorHeart) {
            ImageView heartBadge = new ImageView(context);
            LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(Dim.dp(22), Dim.dp(20));
            hbLp.setMarginStart(Dim.dp(2));
            heartBadge.setLayoutParams(hbLp);
            heartBadge.setImageDrawable(new CreatorHeartDrawable(getCurrentVideoAuthorHandle(), Dim.dp(22), Dim.dp(20)));
            heartBadge.setTag(0x7F0B5A10, (Runnable) () -> {
                String author = getCurrentVideoAuthorHandle();
                Utils.showToastShort(str("morphe_stage_comments_hearted_by", author));
            });
            actionRow.addView(heartBadge);
        }
        contentCol.addView(actionRow);
        if (!isReply && !comment.replies.isEmpty()) {
            LinearLayout repliesToggle = new LinearLayout(context);
            repliesToggle.setOrientation(LinearLayout.HORIZONTAL);
            repliesToggle.setGravity(Gravity.CENTER_VERTICAL);
            repliesToggle.setPadding(0, Dim.dp(4), Dim.dp(12), Dim.dp(4));
            TextView repliesTv = new TextView(context);
            repliesTv.setText(str("morphe_stage_comments_replies_count", String.valueOf(comment.replies.size())));
            repliesTv.setTextColor(accentBlueColor);
            repliesTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            repliesTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            repliesToggle.addView(repliesTv);
            repliesToggle.setTag(0x7F0B5A10, (Runnable) () -> {
                comment.repliesExpanded = !comment.repliesExpanded;
                saveAllStageComments(getAllStageComments());
            });
            contentCol.addView(repliesToggle);
        }
        FrameLayout menuBtn = new FrameLayout(context);
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(Dim.dp(32), Dim.dp(32));
        menuBtn.setLayoutParams(menuLp);
        ImageView menuIcon = new ImageView(context);
        menuIcon.setLayoutParams(new FrameLayout.LayoutParams(Dim.dp(18), Dim.dp(18), Gravity.CENTER));
        menuIcon.setImageDrawable(new VectorOverflowDotsDrawable(secondaryTextColor));
        menuBtn.addView(menuIcon);
        menuBtn.setTag(0x7F0B5A10, (Runnable) () -> {
            Activity act = findActivity(context);
            if (act != null) {
                showStageCommentItemMenuDialog(act, comment);
            }
        });
        row.addView(avatarView);
        row.addView(contentCol);
        row.addView(menuBtn);
        outerWrapper.addView(row);
        if (!isReply && !comment.replies.isEmpty() && comment.repliesExpanded) {
            for (StageComment reply : comment.replies) {
                outerWrapper.addView(buildStageCommentView(context, reply, true));
            }
        }
        return outerWrapper;
    }
    private static String formatAuthorHandle(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return "@User";
        String t = raw.trim();
        return t.startsWith("@") ? t : "@" + t;
    }
    private static BadgeType resolveCommentBadge(StageComment comment) {
        if (comment.badgeType != null && !comment.badgeType.equalsIgnoreCase("AUTO")) {
            return BadgeType.fromString(comment.badgeType);
        }
        BadgeType fromHandle = getBadgeForChannel(comment.authorHandle);
        if (fromHandle != BadgeType.NONE) return fromHandle;
        if (comment.authorName != null && !comment.authorName.isEmpty()) {
            return getBadgeForChannel(comment.authorName);
        }
        return BadgeType.NONE;
    }
    private static String getDisplayLikeCount(StageComment comment) {
        String raw = comment.likeCount != null ? comment.likeCount.trim() : "";
        if (!comment.isLikedByMe) {
            return raw.equals("0") ? "" : raw;
        }
        if (raw.isEmpty() || raw.equals("0")) {
            return "1";
        }
        try {
            int val = Integer.parseInt(raw);
            return String.valueOf(val + 1);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }
    @Nullable
    private static Long attachBodySpansAndFindFirstTimestamp(TextView tv, @Nullable String text, int linkColor) {
        if (text == null || text.isEmpty()) {
            tv.setText("");
            return null;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        Long firstMs = null;
        Matcher tsMatcher = TIMESTAMP_PATTERN.matcher(text);
        while (tsMatcher.find()) {
            String ts = tsMatcher.group(1);
            long ms = parseTimestampToMs(ts);
            if (ms >= 0) {
                if (firstMs == null) firstMs = ms;
                sb.setSpan(new ForegroundColorSpan(linkColor),
                        tsMatcher.start(1), tsMatcher.end(1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        Matcher mentionMatcher = Pattern.compile("(@[A-Za-z0-9_.-]{2,45})").matcher(text);
        while (mentionMatcher.find()) {
            sb.setSpan(new ForegroundColorSpan(linkColor),
                    mentionMatcher.start(1), mentionMatcher.end(1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(sb);
        return firstMs;
    }
    private static long parseTimestampToMs(String ts) {
        try {
            String[] parts = ts.split(":");
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1])) * 1000L;
            } else if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600L + Long.parseLong(parts[1]) * 60L + Long.parseLong(parts[2])) * 1000L;
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }
    private static String getCurrentVideoAuthorHandle() {
        String videoId = VideoInformation.getVideoId();
        if (videoId != null) {
            VideoAuthorInfo info = videoAuthors.get(videoId);
            if (info != null && !info.handle().isEmpty()) {
                return info.handle();
            }
        }
        if (!lastChannelPageHandle.isEmpty()) {
            return lastChannelPageHandle;
        }
        return "@Author";
    }
    private static Drawable getThumbIcon(boolean isUp, boolean filled, int tintColor) {
        String[] candidates = isUp
                ? (filled
                    ? new String[]{"yt_delhi_thumbs_up_filled_24dp", "yt_fill_thumb_up_black_24"}
                    : new String[]{"yt_delhi_thumbs_up_not_filled_24dp", "yt_outline_thumb_up_black_24"})
                : new String[]{"yt_delhi_thumbs_down_not_filled_24dp", "yt_outline_thumb_down_black_24"};
        for (String name : candidates) {
            Drawable d = ResourceUtils.getDrawable(name);
            if (d != null) {
                d = d.mutate();
                d.setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));
                return d;
            }
        }
        return new VectorThumbDrawable(isUp, filled, tintColor);
    }
    private static Drawable getReplyIcon(int tintColor) {
        String[] candidates = {"yt_delhi_comment_24dp", "yt_outline_message_bubble_left_black_24"};
        for (String name : candidates) {
            Drawable d = ResourceUtils.getDrawable(name);
            if (d != null) {
                d = d.mutate();
                d.setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));
                return d;
            }
        }
        return new VectorReplyBubbleDrawable(tintColor);
    }
    private static final class CircularAvatarDrawable extends Drawable {
        private final String handle;
        private final int sizePx;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        CircularAvatarDrawable(String handle, int sizePx) {
            this.handle = handle != null ? handle.trim() : "@User";
            this.sizePx = sizePx;
            setBounds(0, 0, sizePx, sizePx);
            ensureAvatarFetched(this.handle);
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) * 0.5f;
            String normKey = normalizeChannelKey(handle);
            Bitmap bmp = avatarMemoryCache.get(normKey);
            if (bmp != null && !bmp.isRecycled()) {
                canvas.save();
                canvas.translate(b.left, b.top);
                float scale = (r * 2f) / bmp.getWidth();
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shader.setLocalMatrix(m);
                bmpPaint.setShader(shader);
                canvas.drawCircle(r, r, r, bmpPaint);
                canvas.restore();
                return;
            }
            int colorIdx = Math.abs(normKey.hashCode()) % AVATAR_PALETTE.length;
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(AVATAR_PALETTE[colorIdx]);
            canvas.drawCircle(cx, cy, r, bgPaint);
            String clean = handle.startsWith("@") && handle.length() > 1 ? handle.substring(1) : handle;
            String initial = clean.isEmpty() ? "U" : clean.substring(0, 1).toUpperCase(Locale.ROOT);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textPaint.setTextSize(r * 1.05f);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(initial, cx, textY, textPaint);
        }
        @Override
        public void setAlpha(int alpha) {
        }
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    private static final class CreatorHeartDrawable extends Drawable {
        private final CircularAvatarDrawable authorAvatar;
        private final int widthPx;
        private final int heightPx;
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint heartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CreatorHeartDrawable(String authorHandle, int widthPx, int heightPx) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            int avatarDiameter = Math.round(heightPx * 0.80f);
            this.authorAvatar = new CircularAvatarDrawable(authorHandle, avatarDiameter);
            this.authorAvatar.setBounds(0, 0, avatarDiameter, avatarDiameter);
            setBounds(0, 0, widthPx, heightPx);
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            canvas.save();
            canvas.translate(b.left, b.top);
            int avatarDiameter = Math.round(heightPx * 0.80f);
            authorAvatar.setBounds(0, (heightPx - avatarDiameter) / 2, avatarDiameter, (heightPx + avatarDiameter) / 2);
            authorAvatar.draw(canvas);
            float heartCx = widthPx - heightPx * 0.24f;
            float heartCy = heightPx - heightPx * 0.24f;
            float heartRadius = heightPx * 0.24f;
            ringPaint.setStyle(Paint.Style.FILL);
            ringPaint.setColor(isDarkTheme() ? 0xFF0F0F0F : 0xFFFFFFFF);
            canvas.drawCircle(heartCx, heartCy, heartRadius + Dim.dp(1), ringPaint);
            redPaint.setStyle(Paint.Style.FILL);
            redPaint.setColor(0xFFFF0000);
            canvas.drawCircle(heartCx, heartCy, heartRadius, redPaint);
            heartPaint.setStyle(Paint.Style.FILL);
            heartPaint.setColor(0xFFFFFFFF);
            float hs = heartRadius * 1.15f;
            Path hp = new Path();
            hp.moveTo(heartCx, heartCy + hs * 0.45f);
            hp.cubicTo(heartCx - hs * 0.80f, heartCy - hs * 0.05f,
                    heartCx - hs * 0.45f, heartCy - hs * 0.65f,
                    heartCx, heartCy - hs * 0.22f);
            hp.cubicTo(heartCx + hs * 0.45f, heartCy - hs * 0.65f,
                    heartCx + hs * 0.80f, heartCy - hs * 0.05f,
                    heartCx, heartCy + hs * 0.45f);
            hp.close();
            canvas.drawPath(hp, heartPaint);
            canvas.restore();
        }
        @Override
        public void setAlpha(int alpha) {
        }
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    private static final class VectorPinDrawable extends Drawable {
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        VectorPinDrawable(int color) {
            this.color = color;
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(s, s);
            Path p = new Path();
            p.moveTo(16f, 9f);
            p.lineTo(16f, 4f);
            p.lineTo(17f, 4f);
            p.lineTo(17f, 2f);
            p.lineTo(7f, 2f);
            p.lineTo(7f, 4f);
            p.lineTo(8f, 4f);
            p.lineTo(8f, 9f);
            p.lineTo(6f, 11f);
            p.lineTo(6f, 13f);
            p.lineTo(11f, 13f);
            p.lineTo(11f, 22f);
            p.lineTo(13f, 22f);
            p.lineTo(13f, 13f);
            p.lineTo(18f, 13f);
            p.lineTo(18f, 11f);
            p.close();
            canvas.drawPath(p, paint);
            canvas.restore();
        }
        @Override
        public void setAlpha(int alpha) {
        }
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    private static final class VectorOverflowDotsDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        VectorOverflowDotsDrawable(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float h = b.height();
            float r = Math.max(1.5f, h * 0.075f);
            canvas.drawCircle(cx, b.top + h * 0.22f, r, paint);
            canvas.drawCircle(cx, b.top + h * 0.50f, r, paint);
            canvas.drawCircle(cx, b.top + h * 0.78f, r, paint);
        }
        @Override
        public void setAlpha(int alpha) {
        }
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    private static final class VectorThumbDrawable extends Drawable {
        private final boolean isUp;
        private final boolean filled;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        VectorThumbDrawable(boolean isUp, boolean filled, int color) {
            this.isUp = isUp;
            this.filled = filled;
            paint.setColor(color);
            paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(Dim.dp(1.5f));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            canvas.save();
            canvas.translate(b.left, b.top);
            if (!isUp) {
                canvas.translate(b.width(), b.height());
                canvas.scale(-1f, -1f);
            }
            canvas.scale(s, s);
            Path p = new Path();
            p.moveTo(7f, 11f);
            p.lineTo(11f, 3f);
            p.cubicTo(12.1f, 3f, 13f, 3.9f, 13f, 5f);
            p.lineTo(12.3f, 9f);
            p.lineTo(19f, 9f);
            p.cubicTo(20.1f, 9f, 21f, 9.9f, 20.8f, 11f);
            p.lineTo(19.2f, 18.5f);
            p.cubicTo(19.0f, 19.4f, 18.2f, 20f, 17.2f, 20f);
            p.lineTo(7f, 20f);
            p.close();
            p.moveTo(3f, 11f);
            p.lineTo(7f, 11f);
            p.lineTo(7f, 20f);
            p.lineTo(3f, 20f);
            p.close();
            canvas.drawPath(p, paint);
            canvas.restore();
        }
        @Override
        public void setAlpha(int alpha) {
        }
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    private static final class VectorReplyBubbleDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        VectorReplyBubbleDrawable(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Dim.dp(1.5f));
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(s, s);
            Path p = new Path();
            p.moveTo(4f, 4f);
            p.lineTo(20f, 4f);
            p.lineTo(20f, 16f);
            p.lineTo(8f, 16f);
            p.lineTo(4f, 20f);
            p.close();
            canvas.drawPath(p, paint);
            canvas.restore();
        }
        @Override
        public void setAlpha(int alpha) {
        }
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    private static void ensureAvatarFetched(@Nullable String rawHandle) {
        if (rawHandle == null || rawHandle.trim().isEmpty()) return;
        final String normKey = normalizeChannelKey(rawHandle);
        if (normKey.isEmpty() || avatarMemoryCache.get(normKey) != null || !pendingAvatarFetches.add(normKey)) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                Context ctx = Utils.getContext();
                File cacheFile = null;
                if (ctx != null) {
                    File dir = new File(ctx.getCacheDir(), "morphe_stage_avatars");
                    if (!dir.exists()) dir.mkdirs();
                    String safeName = normKey.replaceAll("[^a-z0-9_.-]", "_");
                    cacheFile = new File(dir, safeName + ".png");
                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        try (FileInputStream fis = new FileInputStream(cacheFile)) {
                            Bitmap diskBmp = BitmapFactory.decodeStream(fis);
                            if (diskBmp != null) {
                                avatarMemoryCache.put(normKey, diskBmp);
                                mainHandler.post(StageCommentsAndVerificationPatch::invalidateAllCommentRecyclerViews);
                                return;
                            }
                        }
                    }
                }
                String avatarUrl = handleAvatarUrls.get(normKey);
                if (avatarUrl == null) {
                    String handlePath = normKey.startsWith("@") ? normKey : "@" + normKey;
                    HttpURLConnection pageConn = null;
                    try {
                        URL pageUrl = new URL("https://www.youtube.com/" + handlePath);
                        pageConn = (HttpURLConnection) pageUrl.openConnection();
                        pageConn.setConnectTimeout(6000);
                        pageConn.setReadTimeout(6000);
                        pageConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36");
                        if (pageConn.getResponseCode() == 200) {
                            String html = readStreamToString(pageConn.getInputStream());
                            Matcher m = OG_IMAGE_PATTERN.matcher(html);
                            if (m.find()) {
                                avatarUrl = m.group(1);
                                handleAvatarUrls.put(normKey, avatarUrl);
                            }
                            Matcher tm = OG_TITLE_PATTERN.matcher(html);
                            if (tm.find()) {
                                String title = tm.group(1).replace(" - YouTube", "").trim();
                                if (!title.isEmpty()) {
                                    linkChannelAlias(handlePath, title);
                                }
                            }
                        }
                    } finally {
                        if (pageConn != null) pageConn.disconnect();
                    }
                }
                if (avatarUrl == null || avatarUrl.isEmpty()) {
                    return;
                }
                HttpURLConnection imgConn = null;
                try {
                    URL imgUrl = new URL(avatarUrl);
                    imgConn = (HttpURLConnection) imgUrl.openConnection();
                    imgConn.setConnectTimeout(6000);
                    imgConn.setReadTimeout(6000);
                    if (imgConn.getResponseCode() == 200) {
                        Bitmap rawBmp = BitmapFactory.decodeStream(imgConn.getInputStream());
                        if (rawBmp != null) {
                            int targetSize = 128;
                            Bitmap scaled = Bitmap.createScaledBitmap(rawBmp, targetSize, targetSize, true);
                            avatarMemoryCache.put(normKey, scaled);
                            if (cacheFile != null) {
                                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                                    scaled.compress(Bitmap.CompressFormat.PNG, 90, fos);
                                }
                            }
                            mainHandler.post(StageCommentsAndVerificationPatch::invalidateAllCommentRecyclerViews);
                        }
                    }
                } finally {
                    if (imgConn != null) imgConn.disconnect();
                }
            } catch (Exception ignored) {
            } finally {
                pendingAvatarFetches.remove(normKey);
            }
        });
    }
    private static String readStreamToString(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 256 * 1024) break;
        }
        return bos.toString("UTF-8");
    }
    private static String unescapeJsonString(String s) {
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\u0026", "&");
    }
    @Nullable
    public static Activity findActivity(@Nullable Context context) {
        Context c = context;
        while (c instanceof ContextWrapper cw) {
            if (c instanceof Activity act) return act;
            c = cw.getBaseContext();
        }
        return null;
    }
    public static void showStageManagerDialog(Context context) {
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                str("morphe_stage_comments_manager_title"),
                str("morphe_stage_comments_manager_subtitle"),
                null,
                str("morphe_stage_comments_close"),
                () -> {},
                null,
                null,
                null,
                true
        );
        LinearLayout root = pair.second;
        Dialog dialog = pair.first;
        addDialogActionButton(context, root, str("morphe_stage_comments_action_new_comment"), () -> {
            dialog.dismiss();
            showCreateOrEditStageCommentDialog(context, null, null, null);
        });
        if (!visibleRealComments.isEmpty()) {
            addDialogActionButton(context, root, str("morphe_stage_comments_action_reply_real"), () -> {
                dialog.dismiss();
                showPickRealCommentDialog(context);
            });
        }
        addDialogActionButton(context, root, str("morphe_stage_comments_action_manage_comments"), () -> {
            dialog.dismiss();
            showManageExistingStageCommentsDialog(context);
        });
        addDialogActionButton(context, root, str("morphe_stage_comments_action_manage_verification"), () -> {
            dialog.dismiss();
            showManageVerifiedChannelsDialog(context);
        });
        dialog.show();
    }
    public static void showRealCommentActionDialog(Context context, RealCommentInfo realInfo) {
        String preview = realInfo.authorHandle()
                + (realInfo.bodySnippet().isEmpty() ? "" : ": \"" + realInfo.bodySnippet() + "\"");
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                realInfo.authorHandle(),
                preview,
                null,
                str("morphe_stage_comments_close"),
                () -> {},
                null,
                null,
                null,
                true
        );
        LinearLayout root = pair.second;
        Dialog dialog = pair.first;
        addDialogActionButton(context, root, str("morphe_stage_comments_reply_to_this"), () -> {
            dialog.dismiss();
            showCreateOrEditStageCommentDialog(context, null, null, realInfo);
        });
        addDialogActionButton(context, root, str("morphe_stage_comments_verify_this_author", realInfo.authorHandle()), () -> {
            dialog.dismiss();
            showQuickVerifyChannelDialog(context, realInfo.authorHandle());
        });
        addDialogActionButton(context, root, str("morphe_stage_comments_action_new_comment"), () -> {
            dialog.dismiss();
            showCreateOrEditStageCommentDialog(context, null, null, null);
        });
        dialog.show();
    }
    public static void showStageCommentItemMenuDialog(Context context, StageComment target) {
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                formatAuthorHandle(target.authorHandle),
                target.text,
                null,
                str("morphe_stage_comments_close"),
                () -> {},
                null,
                null,
                null,
                true
        );
        LinearLayout root = pair.second;
        Dialog dialog = pair.first;
        if (!target.isReplyToRealComment()) {
            addDialogActionButton(context, root, str("morphe_stage_comments_add_reply"), () -> {
                dialog.dismiss();
                showCreateOrEditStageCommentDialog(context, null, target, null);
            });
        }
        addDialogActionButton(context, root, str("morphe_stage_comments_edit"), () -> {
            dialog.dismiss();
            showCreateOrEditStageCommentDialog(context, target, null, null);
        });
        addDialogActionButton(context, root,
                target.hasCreatorHeart
                        ? str("morphe_stage_comments_remove_heart")
                        : str("morphe_stage_comments_add_heart"),
                () -> {
                    dialog.dismiss();
                    target.hasCreatorHeart = !target.hasCreatorHeart;
                    saveAllStageComments(getAllStageComments());
                });
        addDialogActionButton(context, root, str("morphe_stage_comments_verify_this_author", formatAuthorHandle(target.authorHandle)), () -> {
            dialog.dismiss();
            showQuickVerifyChannelDialog(context, formatAuthorHandle(target.authorHandle));
        });
        addDialogActionButton(context, root, str("morphe_stage_comments_delete"), () -> {
            dialog.dismiss();
            deleteStageCommentOrReply(target);
        });
        dialog.show();
    }
    private static void deleteStageCommentOrReply(StageComment target) {
        List<StageComment> all = new ArrayList<>(getAllStageComments());
        if (!all.remove(target)) {
            for (StageComment top : all) {
                if (top.replies.remove(target)) {
                    break;
                }
            }
        }
        saveAllStageComments(all);
    }
    public static void showPickRealCommentDialog(Context context) {
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                str("morphe_stage_comments_action_reply_real"),
                null,
                null,
                str("morphe_stage_comments_close"),
                () -> {},
                null,
                null,
                null,
                true
        );
        LinearLayout root = pair.second;
        Dialog dialog = pair.first;
        for (RealCommentInfo info : visibleRealComments.values()) {
            String label = info.authorHandle()
                    + (info.bodySnippet().isEmpty() ? "" : " — " + info.bodySnippet());
            addDialogActionButton(context, root, label, () -> {
                dialog.dismiss();
                showCreateOrEditStageCommentDialog(context, null, null, info);
            });
        }
        dialog.show();
    }
    public static void showCreateOrEditStageCommentDialog(
            Context context,
            @Nullable StageComment editingComment,
            @Nullable StageComment parentStageComment,
            @Nullable RealCommentInfo targetRealComment
    ) {
        final boolean isEditing = editingComment != null;
        final boolean isReply = parentStageComment != null || targetRealComment != null
                || (isEditing && editingComment.isReplyToRealComment());
        String dialogTitle;
        if (isEditing) {
            dialogTitle = str("morphe_stage_comments_edit");
        } else if (targetRealComment != null) {
            dialogTitle = str("morphe_stage_comments_reply_to_user", targetRealComment.authorHandle());
        } else if (parentStageComment != null) {
            dialogTitle = str("morphe_stage_comments_reply_to_user", formatAuthorHandle(parentStageComment.authorHandle));
        } else {
            dialogTitle = str("morphe_stage_comments_action_new_comment");
        }
        ScrollView scrollView = new ScrollView(context);
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Dim.dp(4), Dim.dp(4), Dim.dp(4), Dim.dp(4));
        scrollView.addView(form);
        int textColor = isDarkTheme() ? 0xFFF1F1F1 : 0xFF0F0F0F;
        int hintColor = isDarkTheme() ? 0xFF888888 : 0xFF777777;
        EditText authorInput = createFormEditText(context,
                str("morphe_stage_comments_field_author_hint"),
                isEditing ? editingComment.authorHandle : "@SpecterChannel",
                false, textColor, hintColor);
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_author_label"), textColor));
        form.addView(authorInput);
        Button useMyChannelBtn = CustomDialog.createButton(
                context,
                null,
                str("morphe_stage_comments_use_own_channel", getPrimaryOwnHandle()),
                () -> authorInput.setText(getPrimaryOwnHandle()),
                false,
                false
        );
        form.addView(useMyChannelBtn);
        EditText textInput = createFormEditText(context,
                str("morphe_stage_comments_field_text_hint"),
                isEditing ? editingComment.text : "",
                true, textColor, hintColor);
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_text_label"), textColor));
        form.addView(textInput);
        EditText likesInput = createFormEditText(context,
                str("morphe_stage_comments_field_likes_hint"),
                isEditing ? editingComment.likeCount : "1,2 тыс.",
                false, textColor, hintColor);
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_likes_label"), textColor));
        form.addView(likesInput);
        EditText timeInput = createFormEditText(context,
                str("morphe_stage_comments_field_time_hint"),
                isEditing ? editingComment.timeAgo : str("morphe_stage_comments_default_time"),
                false, textColor, hintColor);
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_time_label"), textColor));
        form.addView(timeInput);
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_badge_label"), textColor));
        RadioGroup badgeGroup = new RadioGroup(context);
        badgeGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbAuto = createRadioButton(context, str("morphe_stage_comments_badge_auto"), 1, textColor);
        RadioButton rbCheck = createRadioButton(context, str("morphe_stage_comments_badge_check"), 2, textColor);
        RadioButton rbArtist = createRadioButton(context, str("morphe_stage_comments_badge_artist"), 3, textColor);
        RadioButton rbNone = createRadioButton(context, str("morphe_stage_comments_badge_none"), 4, textColor);
        badgeGroup.addView(rbAuto);
        badgeGroup.addView(rbCheck);
        badgeGroup.addView(rbArtist);
        badgeGroup.addView(rbNone);
        String currentBadge = isEditing && editingComment.badgeType != null ? editingComment.badgeType : "AUTO";
        if ("CHECKMARK".equalsIgnoreCase(currentBadge)) {
            rbCheck.setChecked(true);
        } else if ("ARTIST".equalsIgnoreCase(currentBadge)) {
            rbArtist.setChecked(true);
        } else if ("NONE".equalsIgnoreCase(currentBadge)) {
            rbNone.setChecked(true);
        } else {
            rbAuto.setChecked(true);
        }
        form.addView(badgeGroup);
        CheckBox heartCb = new CheckBox(context);
        heartCb.setText(str("morphe_stage_comments_field_creator_heart"));
        heartCb.setTextColor(textColor);
        heartCb.setChecked(isEditing && editingComment.hasCreatorHeart);
        form.addView(heartCb);
        CheckBox pinnedCb = new CheckBox(context);
        pinnedCb.setText(str("morphe_stage_comments_field_pinned"));
        pinnedCb.setTextColor(textColor);
        pinnedCb.setChecked(isEditing && editingComment.isPinned);
        if (!isReply) {
            form.addView(pinnedCb);
        }
        CheckBox globalCb = new CheckBox(context);
        globalCb.setText(str("morphe_stage_comments_field_global"));
        globalCb.setTextColor(textColor);
        globalCb.setChecked(isEditing ? "*".equals(editingComment.videoId)
                : (VideoInformation.getVideoId() == null || VideoInformation.getVideoId().isEmpty()));
        if (parentStageComment == null) {
            form.addView(globalCb);
        }
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                dialogTitle,
                null,
                null,
                str("morphe_stage_comments_save"),
                () -> {
                    String rawAuthor = authorInput.getText().toString().trim();
                    String rawText = textInput.getText().toString().trim();
                    if (rawText.isEmpty()) {
                        Utils.showToastShort(str("morphe_stage_comments_empty_text_warning"));
                        return;
                    }
                    String formattedAuthor = formatAuthorHandle(rawAuthor);
                    ensureAvatarFetched(formattedAuthor);
                    String chosenBadge = "AUTO";
                    int checkedId = badgeGroup.getCheckedRadioButtonId();
                    if (checkedId == 2) {
                        chosenBadge = "CHECKMARK";
                        setChannelVerification(formattedAuthor, BadgeType.CHECKMARK_100K);
                    } else if (checkedId == 3) {
                        chosenBadge = "ARTIST";
                        setChannelVerification(formattedAuthor, BadgeType.ARTIST_NOTE);
                    } else if (checkedId == 4) {
                        chosenBadge = "NONE";
                    }
                    List<StageComment> all = new ArrayList<>(getAllStageComments());
                    StageComment target = isEditing ? editingComment : new StageComment();
                    target.authorHandle = formattedAuthor;
                    target.text = rawText;
                    target.likeCount = likesInput.getText().toString().trim();
                    target.timeAgo = timeInput.getText().toString().trim();
                    target.badgeType = chosenBadge;
                    target.hasCreatorHeart = heartCb.isChecked();
                    target.isPinned = !isReply && pinnedCb.isChecked();
                    String currentVid = VideoInformation.getVideoId();
                    target.videoId = (globalCb.isChecked() || currentVid == null || currentVid.isEmpty())
                            ? "*"
                            : currentVid;
                    if (!isEditing) {
                        if (parentStageComment != null) {
                            parentStageComment.replies.add(target);
                            parentStageComment.repliesExpanded = true;
                        } else if (targetRealComment != null) {
                            target.targetRealCommentAuthor = targetRealComment.authorHandle();
                            target.targetRealCommentSnippet = targetRealComment.bodySnippet();
                            all.add(target);
                        } else {
                            all.add(0, target);
                        }
                    }
                    saveAllStageComments(all);
                },
                () -> {},
                null,
                null,
                true
        );
        pair.second.addView(scrollView, Math.max(0, pair.second.getChildCount() - 1));
        pair.first.show();
    }
    public static void showQuickVerifyChannelDialog(Context context, String channelHandleOrName) {
        BadgeType current = getBadgeForChannel(channelHandleOrName);
        RadioGroup rg = new RadioGroup(context);
        rg.setOrientation(RadioGroup.VERTICAL);
        rg.setPadding(Dim.dp(8), Dim.dp(8), Dim.dp(8), Dim.dp(8));
        int textColor = isDarkTheme() ? 0xFFF1F1F1 : 0xFF0F0F0F;
        RadioButton rbNone = createRadioButton(context, str("morphe_stage_comments_badge_none"), 10, textColor);
        RadioButton rbCheck = createRadioButton(context, str("morphe_stage_comments_badge_check"), 11, textColor);
        RadioButton rbArtist = createRadioButton(context, str("morphe_stage_comments_badge_artist"), 12, textColor);
        rg.addView(rbNone);
        rg.addView(rbCheck);
        rg.addView(rbArtist);
        if (current == BadgeType.CHECKMARK_100K) {
            rbCheck.setChecked(true);
        } else if (current == BadgeType.ARTIST_NOTE) {
            rbArtist.setChecked(true);
        } else {
            rbNone.setChecked(true);
        }
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                str("morphe_stage_comments_verify_dialog_title", channelHandleOrName),
                null,
                null,
                str("morphe_stage_comments_save"),
                () -> {
                    int id = rg.getCheckedRadioButtonId();
                    BadgeType selected = BadgeType.NONE;
                    if (id == 11) selected = BadgeType.CHECKMARK_100K;
                    else if (id == 12) selected = BadgeType.ARTIST_NOTE;
                    setChannelVerification(channelHandleOrName, selected);
                },
                () -> {},
                null,
                null,
                true
        );
        pair.second.addView(rg, Math.max(0, pair.second.getChildCount() - 1));
        pair.first.show();
    }
    public static void showManageExistingStageCommentsDialog(Context context) {
        List<StageComment> all = getAllStageComments();
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                str("morphe_stage_comments_action_manage_comments"),
                all.isEmpty() ? str("morphe_stage_comments_empty_list") : null,
                null,
                str("morphe_stage_comments_close"),
                () -> {},
                null,
                all.isEmpty() ? null : str("morphe_stage_comments_reset_all"),
                all.isEmpty() ? null : StageCommentsAndVerificationPatch::resetAllStageComments,
                true
        );
        LinearLayout root = pair.second;
        Dialog dialog = pair.first;
        for (StageComment c : all) {
            String prefix = c.isReplyToRealComment()
                    ? "[" + str("morphe_stage_comments_reply_tag", c.targetRealCommentAuthor) + "] "
                    : "";
            String label = prefix + formatAuthorHandle(c.authorHandle) + ": " + c.text;
            addDialogActionButton(context, root, label, () -> {
                dialog.dismiss();
                showStageCommentItemMenuDialog(context, c);
            });
        }
        addDialogActionButton(context, root, str("morphe_stage_comments_action_new_comment"), () -> {
            dialog.dismiss();
            showCreateOrEditStageCommentDialog(context, null, null, null);
        });
        dialog.show();
    }
    public static void showManageVerifiedChannelsDialog(Context context) {
        Map<String, BadgeType> map = getConfiguredVerifiedChannels();
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                str("morphe_stage_comments_action_manage_verification"),
                null,
                null,
                str("morphe_stage_comments_close"),
                () -> {},
                null,
                map.isEmpty() ? null : str("morphe_stage_verification_reset_all"),
                map.isEmpty() ? null : StageCommentsAndVerificationPatch::resetAllChannelVerifications,
                true
        );
        LinearLayout root = pair.second;
        Dialog dialog = pair.first;
        BadgeType ownBadge = BadgeType.fromString(Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.get());
        String ownBadgeLabel = ownBadge == BadgeType.ARTIST_NOTE
                ? str("morphe_stage_comments_badge_artist")
                : (ownBadge == BadgeType.CHECKMARK_100K
                    ? str("morphe_stage_comments_badge_check")
                    : str("morphe_stage_comments_badge_none"));
        addDialogActionButton(context, root,
                str("morphe_stage_verification_own_channel_btn", ownBadgeLabel),
                () -> {
                    dialog.dismiss();
                    showConfigureOwnChannelDialog(context);
                });
        addDialogActionButton(context, root, str("morphe_stage_verification_add_channel_btn"), () -> {
            dialog.dismiss();
            showAddChannelVerificationDialog(context);
        });
        String rawList = Settings.STAGE_VERIFIED_CHANNELS_LIST.get();
        if (rawList != null && !rawList.trim().isEmpty()) {
            for (String line : rawList.split("[\\r\\n]+")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String ch = trimmed;
                BadgeType bt = BadgeType.CHECKMARK_100K;
                int sep = trimmed.lastIndexOf('=');
                if (sep < 0) sep = trimmed.lastIndexOf('|');
                if (sep > 0) {
                    ch = trimmed.substring(0, sep).trim();
                    bt = BadgeType.fromString(trimmed.substring(sep + 1));
                }
                final String targetChannel = ch;
                String badgeText = bt == BadgeType.ARTIST_NOTE
                        ? str("morphe_stage_comments_badge_artist")
                        : str("morphe_stage_comments_badge_check");
                addDialogActionButton(context, root, targetChannel + " — " + badgeText, () -> {
                    dialog.dismiss();
                    showQuickVerifyChannelDialog(context, targetChannel);
                });
            }
        }
        dialog.show();
    }
    public static void showConfigureOwnChannelDialog(Context context) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Dim.dp(8), Dim.dp(4), Dim.dp(8), Dim.dp(4));
        int textColor = isDarkTheme() ? 0xFFF1F1F1 : 0xFF0F0F0F;
        int hintColor = isDarkTheme() ? 0xFF888888 : 0xFF777777;
        form.addView(createFieldLabel(context, str("morphe_stage_own_channel_handles_title"), textColor));
        EditText handlesInput = createFormEditText(context,
                "@MyHandle, My Channel Name",
                Settings.STAGE_OWN_CHANNEL_HANDLES.get(),
                false, textColor, hintColor);
        form.addView(handlesInput);
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_badge_label"), textColor));
        RadioGroup rg = new RadioGroup(context);
        rg.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbNone = createRadioButton(context, str("morphe_stage_comments_badge_none"), 20, textColor);
        RadioButton rbCheck = createRadioButton(context, str("morphe_stage_comments_badge_check"), 21, textColor);
        RadioButton rbArtist = createRadioButton(context, str("morphe_stage_comments_badge_artist"), 22, textColor);
        rg.addView(rbNone);
        rg.addView(rbCheck);
        rg.addView(rbArtist);
        BadgeType current = BadgeType.fromString(Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.get());
        if (current == BadgeType.ARTIST_NOTE) rbArtist.setChecked(true);
        else if (current == BadgeType.CHECKMARK_100K) rbCheck.setChecked(true);
        else rbNone.setChecked(true);
        form.addView(rg);
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                str("morphe_stage_own_channel_dialog_title"),
                null,
                null,
                str("morphe_stage_comments_save"),
                () -> {
                    Settings.STAGE_OWN_CHANNEL_HANDLES.save(handlesInput.getText().toString().trim());
                    int id = rg.getCheckedRadioButtonId();
                    String typeStr = "NONE";
                    if (id == 21) typeStr = "CHECKMARK";
                    else if (id == 22) typeStr = "ARTIST";
                    Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.save(typeStr);
                    triggerVerificationRelayout();
                },
                () -> {},
                null,
                null,
                true
        );
        pair.second.addView(form, Math.max(0, pair.second.getChildCount() - 1));
        pair.first.show();
    }
    public static void showAddChannelVerificationDialog(Context context) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Dim.dp(8), Dim.dp(4), Dim.dp(8), Dim.dp(4));
        int textColor = isDarkTheme() ? 0xFFF1F1F1 : 0xFF0F0F0F;
        int hintColor = isDarkTheme() ? 0xFF888888 : 0xFF777777;
        String defaultChannel = !lastChannelPageHandle.isEmpty()
                ? lastChannelPageHandle
                : (!lastChannelPageTitle.isEmpty() ? lastChannelPageTitle : "@SpecterChannel");
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_author_label"), textColor));
        EditText channelInput = createFormEditText(context,
                "@SpecterChannel",
                defaultChannel,
                false, textColor, hintColor);
        form.addView(channelInput);
        form.addView(createFieldLabel(context, str("morphe_stage_comments_field_badge_label"), textColor));
        RadioGroup rg = new RadioGroup(context);
        rg.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbCheck = createRadioButton(context, str("morphe_stage_comments_badge_check"), 31, textColor);
        RadioButton rbArtist = createRadioButton(context, str("morphe_stage_comments_badge_artist"), 32, textColor);
        rg.addView(rbCheck);
        rg.addView(rbArtist);
        rbCheck.setChecked(true);
        form.addView(rg);
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                str("morphe_stage_verification_add_channel_btn"),
                null,
                null,
                str("morphe_stage_comments_save"),
                () -> {
                    String ch = channelInput.getText().toString().trim();
                    if (!ch.isEmpty()) {
                        BadgeType bt = rg.getCheckedRadioButtonId() == 32
                                ? BadgeType.ARTIST_NOTE
                                : BadgeType.CHECKMARK_100K;
                        setChannelVerification(ch, bt);
                    }
                },
                () -> {},
                null,
                null,
                true
        );
        pair.second.addView(form, Math.max(0, pair.second.getChildCount() - 1));
        pair.first.show();
    }
    private static void addDialogActionButton(Context context, LinearLayout parent, String label, Runnable onClick) {
        Button btn = CustomDialog.createButton(context, null, label, onClick, false, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Dim.dp(6);
        btn.setLayoutParams(lp);
        int insertAt = Math.max(0, parent.getChildCount() - 1);
        parent.addView(btn, insertAt);
    }
    private static TextView createFieldLabel(Context context, String text, int textColor) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setPadding(0, Dim.dp(8), 0, Dim.dp(2));
        return tv;
    }
    private static EditText createFormEditText(Context context, String hint, String initialText,
                                               boolean multiLine, int textColor, int hintColor) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setText(initialText);
        et.setTextColor(textColor);
        et.setHintTextColor(hintColor);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        et.setInputType(multiLine
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                : InputType.TYPE_CLASS_TEXT);
        if (multiLine) {
            et.setMinLines(2);
            et.setMaxLines(5);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isDarkTheme() ? 0xFF1E1E1E : 0xFFF2F2F2);
        bg.setCornerRadius(Dim.dp(8));
        bg.setStroke(Dim.dp(1), isDarkTheme() ? 0xFF383838 : 0xFFD0D0D0);
        et.setBackground(bg);
        et.setPadding(Dim.dp(10), Dim.dp(8), Dim.dp(10), Dim.dp(8));
        return et;
    }
    private static RadioButton createRadioButton(Context context, String label, int id, int textColor) {
        RadioButton rb = new RadioButton(context);
        rb.setId(id);
        rb.setText(label);
        rb.setTextColor(textColor);
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        return rb;
    }
    private static boolean isDarkTheme() {
        return Utils.isDarkModeEnabled();
    }
}
