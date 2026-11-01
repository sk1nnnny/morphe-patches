/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.LinearLayout;

import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.components.StageCommentsAndVerificationPatch;
import app.morphe.extension.youtube.patches.components.StageCommentsAndVerificationPatch.BadgeType;
import app.morphe.extension.youtube.patches.components.StageCommentsAndVerificationPatch.StageComment;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"deprecation", "unused"})
public class StageCommentsPreferenceCategory extends PreferenceCategory {

    private static final Set<String> REFRESH_KEYS = Set.of(
            Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.key,
            Settings.STAGE_OWN_CHANNEL_HANDLES.key,
            Settings.STAGE_VERIFIED_CHANNELS_LIST.key,
            Settings.STAGE_COMMENTS_DATA_JSON.key
    );

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (prefs, key) -> {
                if (REFRESH_KEYS.contains(key)) {
                    Utils.runOnMainThread(this::refresh);
                }
            };

    public StageCommentsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public StageCommentsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StageCommentsPreferenceCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StageCommentsPreferenceCategory(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        buildRows();
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private void refresh() {
        removeAll();
        buildRows();
    }

    private void buildRows() {
        Context context = getContext();

        BadgeType ownBadge = BadgeType.fromString(Settings.STAGE_OWN_CHANNEL_BADGE_TYPE.get());
        String ownBadgeSummary = switch (ownBadge) {
            case ARTIST_NOTE -> str("morphe_stage_comments_badge_artist");
            case CHECKMARK_100K -> str("morphe_stage_comments_badge_check");
            default -> str("morphe_stage_comments_badge_none");
        };
        Preference ownChannelPref = new Preference(context);
        ownChannelPref.setTitle(str("morphe_stage_own_channel_dialog_title"));
        ownChannelPref.setSummary(ownBadgeSummary);
        ownChannelPref.setOnPreferenceClickListener(pref -> {
            StageCommentsAndVerificationPatch.showConfigureOwnChannelDialog(context);
            return true;
        });
        addPreference(ownChannelPref);

        int verifiedLinesCount = countNonEmptyLines(Settings.STAGE_VERIFIED_CHANNELS_LIST.get());
        Preference manageVerifiedPref = new Preference(context);
        manageVerifiedPref.setTitle(str("morphe_stage_comments_action_manage_verification"));
        manageVerifiedPref.setSummary(str("morphe_stage_verification_count_summary", String.valueOf(verifiedLinesCount)));
        manageVerifiedPref.setOnPreferenceClickListener(pref -> {
            StageCommentsAndVerificationPatch.showManageVerifiedChannelsDialog(context);
            return true;
        });
        addPreference(manageVerifiedPref);

        Preference createCommentPref = new Preference(context);
        createCommentPref.setTitle(str("morphe_stage_comments_action_new_comment"));
        createCommentPref.setSummary(str("morphe_stage_comments_create_summary"));
        createCommentPref.setOnPreferenceClickListener(pref -> {
            StageCommentsAndVerificationPatch.showCreateOrEditStageCommentDialog(context, null, null, null);
            return true;
        });
        addPreference(createCommentPref);

        List<StageComment> allComments = StageCommentsAndVerificationPatch.getAllStageComments();
        int totalCommentsAndReplies = allComments.size();
        for (StageComment c : allComments) {
            totalCommentsAndReplies += c.replies.size();
        }
        Preference manageCommentsPref = new Preference(context);
        manageCommentsPref.setTitle(str("morphe_stage_comments_action_manage_comments"));
        manageCommentsPref.setSummary(str("morphe_stage_comments_count_summary", String.valueOf(totalCommentsAndReplies)));
        manageCommentsPref.setOnPreferenceClickListener(pref -> {
            StageCommentsAndVerificationPatch.showManageExistingStageCommentsDialog(context);
            return true;
        });
        addPreference(manageCommentsPref);

        Preference resetCommentsPref = new Preference(context);
        resetCommentsPref.setTitle(str("morphe_stage_comments_reset_all"));
        resetCommentsPref.setSummary(str("morphe_stage_comments_reset_all_summary"));
        resetCommentsPref.setOnPreferenceClickListener(pref -> {
            showConfirmResetDialog(
                    str("morphe_stage_comments_reset_all"),
                    StageCommentsAndVerificationPatch::resetAllStageComments
            );
            return true;
        });
        addPreference(resetCommentsPref);

        Preference resetVerificationPref = new Preference(context);
        resetVerificationPref.setTitle(str("morphe_stage_verification_reset_all"));
        resetVerificationPref.setSummary(str("morphe_stage_verification_reset_all_summary"));
        resetVerificationPref.setOnPreferenceClickListener(pref -> {
            showConfirmResetDialog(
                    str("morphe_stage_verification_reset_all"),
                    StageCommentsAndVerificationPatch::resetAllChannelVerifications
            );
            return true;
        });
        addPreference(resetVerificationPref);
    }

    private static int countNonEmptyLines(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        int count = 0;
        for (String line : s.split("[\\r\\n]+")) {
            if (!line.trim().isEmpty()) count++;
        }
        return count;
    }

    private void showConfirmResetDialog(String title, Runnable onConfirm) {
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                getContext(),
                title,
                null,
                null,
                null,
                () -> {
                    onConfirm.run();
                    refresh();
                },
                () -> {},
                null,
                null,
                true
        );
        dialogPair.first.show();
    }
}
