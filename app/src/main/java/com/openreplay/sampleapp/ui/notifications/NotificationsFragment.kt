package com.openreplay.sampleapp.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.openreplay.sampleapp.databinding.FragmentNotificationsBinding
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.models.script.ORMobileViewComponentEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    private val eventLog = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
    }

    private fun setupButtons() {
        binding.btnCheckout.setOnClickListener {
            trackEcommerceCheckout()
        }

        binding.btnVideoPlay.setOnClickListener {
            trackVideoPlayback()
        }

        binding.btnShare.setOnClickListener {
            trackContentShare()
        }

        binding.btnSetUser.setOnClickListener {
            setUserMetadata()
        }

        binding.btnPremiumUpgrade.setOnClickListener {
            trackPremiumUpgrade()
        }

        binding.btnLogError.setOnClickListener {
            logApplicationError()
        }

        binding.btnCrash.setOnClickListener {
            simulateHandledCrash()
        }
    }

    private fun trackEcommerceCheckout() {
        val cartItems = listOf(
            mapOf("id" to "prod_123", "name" to "Wireless Headphones", "price" to 79.99, "quantity" to 1),
            mapOf("id" to "prod_456", "name" to "Phone Case", "price" to 19.99, "quantity" to 2)
        )

        OpenReplay.event(
            "checkout_started",
            mapOf(
                "cart_items" to cartItems,
                "total_amount" to 119.97,
                "currency" to "USD",
                "items_count" to 3,
                "discount_code" to "SAVE10",
                "shipping_method" to "express"
            )
        )

        logEvent("checkout_started", "Cart: 3 items, Total: \$119.97")
        updateStatus("✅ E-commerce checkout event tracked")
    }

    private fun trackVideoPlayback() {
        OpenReplay.event(
            "video_played",
            mapOf(
                "video_id" to "vid_789",
                "title" to "Product Demo Tutorial",
                "duration" to 180,
                "quality" to "1080p",
                "category" to "education",
                "position" to 0,
                "autoplay" to false,
                "source" to "recommended"
            )
        )

        logEvent("video_played", "Tutorial video started (180s, 1080p)")
        updateStatus("✅ Media playback event tracked")
    }

    private fun trackContentShare() {
        OpenReplay.event(
            "content_shared",
            mapOf(
                "content_type" to "article",
                "content_id" to "art_321",
                "content_title" to "Best Android Development Practices",
                "share_method" to "twitter",
                "share_timestamp" to System.currentTimeMillis(),
                "user_segment" to "power_user"
            )
        )

        logEvent("content_shared", "Article shared via Twitter")
        updateStatus("✅ Social sharing event tracked")
    }

    private fun setUserMetadata() {
        val userId = "user_${System.currentTimeMillis() % 10000}"
        
        OpenReplay.setUserID(userId)
        OpenReplay.setMetadata("subscription", "premium")
        OpenReplay.setMetadata("account_age_days", "45")
        OpenReplay.setMetadata("preferred_language", "en")
        OpenReplay.setMetadata("theme", "dark")
        OpenReplay.setMetadata("notifications_enabled", "true")

        logEvent("user_identified", "User ID: $userId")
        logEvent("metadata_set", "Subscription: premium, Theme: dark")
        updateStatus("✅ User metadata updated successfully")
    }

    private fun trackPremiumUpgrade() {
        val event = ORMobileViewComponentEvent(
            screenName = "NotificationsFragment",
            viewName = "premium_upgrade_modal",
            visible = true
        )
        MessageCollector.sendMessage(event)

        OpenReplay.event(
            "premium_upgrade_completed",
            mapOf(
                "plan" to "annual",
                "price" to 99.99,
                "currency" to "USD",
                "payment_method" to "credit_card",
                "previous_plan" to "free",
                "discount_applied" to 20,
                "billing_cycle" to "yearly",
                "features_unlocked" to listOf("ad_free", "offline_mode", "premium_support")
            )
        )

        logEvent("premium_upgrade", "Annual plan: \$99.99 (20% discount)")
        updateStatus("✅ Premium upgrade event tracked")
    }

    private fun logApplicationError() {
        val errorDetails = mapOf(
            "error_code" to "ERR_NETWORK_TIMEOUT",
            "error_message" to "Failed to fetch user profile: Connection timeout",
            "endpoint" to "/api/v1/user/profile",
            "http_status" to 408,
            "retry_count" to 3,
            "timestamp" to System.currentTimeMillis(),
            "user_action" to "profile_refresh",
            "device_online" to true
        )

        OpenReplay.event("application_error", errorDetails)

        logEvent("application_error", "ERR_NETWORK_TIMEOUT at /api/v1/user/profile")
        updateStatus("⚠️ Application error logged")
    }

    private fun simulateHandledCrash() {
        try {
            throw RuntimeException("Simulated error: Invalid state transition from IDLE to PROCESSING")
        } catch (e: Exception) {
            OpenReplay.event(
                "exception_caught",
                mapOf(
                    "exception_type" to e.javaClass.simpleName,
                    "exception_message" to e.message,
                    "stack_trace" to e.stackTraceToString().take(500),
                    "handled" to true,
                    "severity" to "medium",
                    "context" to "NotificationsFragment",
                    "user_impact" to "feature_unavailable"
                )
            )

            logEvent("exception_caught", "RuntimeException (handled)")
            updateStatus("✅ Crash simulated and logged (handled)")
        }
    }

    private fun logEvent(eventName: String, details: String) {
        val timestamp = timeFormat.format(Date())
        val logEntry = "[$timestamp] $eventName: $details"
        eventLog.add(0, logEntry)
        
        if (eventLog.size > 8) {
            eventLog.removeAt(eventLog.size - 1)
        }
        
        binding.textEventLog.text = eventLog.joinToString("\n\n")
    }

    private fun updateStatus(message: String) {
        binding.textStatus.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}