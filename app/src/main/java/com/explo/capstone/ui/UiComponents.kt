package com.explo.capstone.ui

import android.content.Context
import android.view.View
import com.explo.capstone.shared.MessageCategory

/**
 * Owner: Tamminana Yashwanth Sai
 * Responsible for: XML layouts, custom views, and the visual design system.
 *
 * TODO Yashwanth:
 *  1. Create res/layout/fragment_mission_list.xml — list of active missions
 *  2. Create res/layout/fragment_chat.xml — chat thread with message bubbles
 *  3. Create res/layout/item_message.xml — single message row
 *  4. Create res/layout/activity_main.xml — NavHostFragment container
 *  5. Implement MessageBubbleView with category colour coding (see categoryColor below)
 *  6. Create res/values/colors.xml and res/values/styles.xml for the design system
 */
object CategoryColors {

    /**
     * Returns the color res ID to use for a message bubble based on its category.
     * Use this in item_message.xml to visually distinguish message types.
     */
    fun categoryColor(category: MessageCategory): Int {
        return when (category) {
            MessageCategory.COMMAND      -> android.R.color.holo_red_light
            MessageCategory.INTELLIGENCE -> android.R.color.holo_orange_light
            MessageCategory.STANDARD     -> android.R.color.holo_blue_light
            MessageCategory.RESTRICTED   -> android.R.color.darker_gray
        }
    }
}
