/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.stagecomments

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.litho.relayout.lithoRelayoutPatch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.screenInfoPreferenceCategory
import app.morphe.patches.shared.misc.spans.addSpanFilter
import app.morphe.patches.shared.misc.spans.inclusiveSpanPatch
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/components/StageCommentsAndVerificationPatch;"
private const val VERIFICATION_SPAN_FILTER =
    $$"Lapp/morphe/extension/youtube/patches/components/StageCommentsAndVerificationPatch$VerificationSpanFilter;"

@Suppress("unused")
val channelVerificationAndStageCommentsPatch = bytecodePatch(
    name = "Channel verification and stage comments",
    description = "Allows visually adding 100K or Official Artist verification badges to your own channel or any other channel, and creating stage comments and replies under videos."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        elementProtoParserHookPatch,
        inclusiveSpanPatch,
        textComponentPatch,
        lithoSpannableStringPatch,
        lithoRelayoutPatch,
        videoInformationPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_stage_comments_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_stage_verification_enabled", summary = true),
                    SwitchPreference("morphe_stage_comments_enabled", summary = true),
                    SwitchPreference("morphe_stage_comments_long_press_hint", summary = true),
                    TextPreference("morphe_stage_own_channel_handles", inputType = InputType.TEXT_MULTI_LINE),
                    TextPreference("morphe_stage_verified_channels_list", inputType = InputType.TEXT_MULTI_LINE),
                    PreferenceCategory(
                        key = "morphe_stage_comments_actions_category",
                        sorting = Sorting.UNSORTED,
                        preferences = emptySet(),
                        tag = "app.morphe.extension.youtube.settings.preference.StageCommentsPreferenceCategory"
                    ),
                    screenInfoPreferenceCategory(
                        key = "morphe_stage_comments_about_category",
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_stage_comments_about",
                                summaryKey = null
                            )
                        )
                    )
                )
            )
        )

        hookElement("$EXTENSION_CLASS->onElementLoaded")
        hookLithoSpannableString(EXTENSION_CLASS)
        addSpanFilter(VERIFICATION_SPAN_FILTER)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
