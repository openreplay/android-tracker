package com.openreplay.sampleapp.ui.home

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.openreplay.sampleapp.R
import com.openreplay.sampleapp.databinding.BottomsheetDemoBinding
import com.openreplay.sampleapp.databinding.DialogCustomBinding
import com.openreplay.sampleapp.databinding.FragmentHomeBinding
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.listeners.Analytics
import com.openreplay.tracker.listeners.SwipeDirection
import com.openreplay.tracker.listeners.sanitize
import com.openreplay.tracker.managers.ScreenshotManager
import kotlin.math.abs

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val homeViewModel by lazy { ViewModelProvider(this)[HomeViewModel::class.java] }
    private var isSanitized = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupInputTracking()
        setupSwipeDetection()
        setupButtons()
    }

    private fun setupInputTracking() {
        binding.inputSanitized.sanitize()
        
        val sessionId = OpenReplay.getSessionID()
        updateStatus("✅ Tracker Active | Session ID: ${sessionId ?: "Initializing..."}")
    }

    private fun setupSwipeDetection() {
        var startX = 0f
        var startY = 0f
        
        binding.swipeArea.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val endX = event.x
                    val endY = event.y
                    val deltaX = endX - startX
                    val deltaY = endY - startY
                    
                    if (abs(deltaX) > 100 || abs(deltaY) > 100) {
                        val direction = when {
                            abs(deltaX) > abs(deltaY) -> {
                                if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                            }
                            else -> {
                                if (deltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
                            }
                        }
                        
                        Analytics.sendSwipe(direction, endX, endY)
                        updateStatus("Swipe detected: $direction")
                        binding.textSwipeHint.text = "Swipe detected: $direction\n⬅️ ➡️ ⬆️ ⬇️"
                        
                        view.postDelayed({
                            binding.textSwipeHint.text = "👆 Swipe Here 👆\n⬅️ ➡️ ⬆️ ⬇️"
                        }, 2000)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        binding.btnToggleSanitize.setOnClickListener {
            isSanitized = !isSanitized
            if (isSanitized) {
                binding.inputSanitized.sanitize()
                binding.btnToggleSanitize.text = "Toggle Sanitization (Currently: ON)"
                updateStatus("✅ Sanitization ENABLED - Field masked in screenshots")
            } else {
                ScreenshotManager.removeSanitizedElement(binding.inputSanitized)
                binding.btnToggleSanitize.text = "Toggle Sanitization (Currently: OFF)"
                updateStatus("⚠️ Sanitization DISABLED - Field visible in screenshots")
            }
        }

        binding.btnShowBottomsheet.setOnClickListener {
            showBottomSheet()
        }

        binding.btnShowDialog.setOnClickListener {
            showAlertDialog()
        }

        binding.btnShowCustomDialog.setOnClickListener {
            showCustomDialog()
        }

        binding.btnShowSnackbar.setOnClickListener {
            showSnackbar()
        }
    }

    private fun showBottomSheet() {
        val bottomSheet = BottomSheetDialog(requireContext())
        val bottomSheetBinding = BottomsheetDemoBinding.inflate(layoutInflater)
        bottomSheet.setContentView(bottomSheetBinding.root)

        bottomSheetBinding.btnBottomsheetSubmit.setOnClickListener {
            val name = bottomSheetBinding.bottomsheetInputName.text.toString()
            val email = bottomSheetBinding.bottomsheetInputEmail.text.toString()
            
            OpenReplay.event(
                "bottomsheet_form_submitted",
                mapOf(
                    "name" to name,
                    "email" to email,
                    "source" to "demo_bottomsheet"
                )
            )
            
            updateStatus("✅ Bottom sheet form submitted: $name")
            bottomSheet.dismiss()
        }

        bottomSheetBinding.btnBottomsheetCancel.setOnClickListener {
            OpenReplay.event("bottomsheet_cancelled", mapOf("action" to "user_cancelled"))
            bottomSheet.dismiss()
        }

        OpenReplay.event("bottomsheet_opened", mapOf("type" to "demo_form"))
        bottomSheet.show()
    }

    private fun showAlertDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("🎯 Alert Dialog")
            .setMessage("This is a standard Android AlertDialog. All interactions are tracked by OpenReplay.")
            .setPositiveButton("Confirm") { dialog, _ ->
                OpenReplay.event("alert_dialog_confirmed", mapOf("action" to "positive"))
                updateStatus("✅ Alert dialog confirmed")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                OpenReplay.event("alert_dialog_cancelled", mapOf("action" to "negative"))
                updateStatus("⚠️ Alert dialog cancelled")
                dialog.dismiss()
            }
            .setNeutralButton("More Info") { _, _ ->
                OpenReplay.event("alert_dialog_info", mapOf("action" to "neutral"))
                updateStatus("ℹ️ More info requested")
            }
            .show()
        
        OpenReplay.event("alert_dialog_shown", mapOf("type" to "demo_alert"))
    }

    private fun showCustomDialog() {
        val dialog = AlertDialog.Builder(requireContext()).create()
        val dialogBinding = DialogCustomBinding.inflate(layoutInflater)
        dialog.setView(dialogBinding.root)

        dialogBinding.btnDialogOk.setOnClickListener {
            val username = dialogBinding.dialogInputUsername.text.toString()
            val remember = dialogBinding.dialogCheckboxRemember.isChecked
            
            OpenReplay.event(
                "custom_dialog_submitted",
                mapOf(
                    "username" to username,
                    "remember_me" to remember,
                    "dialog_type" to "login_form"
                )
            )
            
            updateStatus("✅ Custom dialog submitted: $username (Remember: $remember)")
            dialog.dismiss()
        }

        dialogBinding.btnDialogCancel.setOnClickListener {
            OpenReplay.event("custom_dialog_cancelled", mapOf("action" to "cancel"))
            dialog.dismiss()
        }

        OpenReplay.event("custom_dialog_opened", mapOf("type" to "login_preferences"))
        dialog.show()
    }

    private fun showSnackbar() {
        Snackbar.make(binding.root, "📢 This is a Snackbar with an action!", Snackbar.LENGTH_LONG)
            .setAction("UNDO") {
                OpenReplay.event("snackbar_action_clicked", mapOf("action" to "undo"))
                updateStatus("✅ Snackbar action clicked")
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onShown(sb: Snackbar?) {
                    OpenReplay.event("snackbar_shown", mapOf("message" to "demo_snackbar"))
                }
                
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    val dismissReason = when (event) {
                        DISMISS_EVENT_ACTION -> "action_clicked"
                        DISMISS_EVENT_TIMEOUT -> "timeout"
                        DISMISS_EVENT_MANUAL -> "manual"
                        DISMISS_EVENT_CONSECUTIVE -> "consecutive"
                        else -> "unknown"
                    }
                    OpenReplay.event("snackbar_dismissed", mapOf("reason" to dismissReason))
                }
            })
            .show()
        
        updateStatus("📢 Snackbar displayed")
    }
    
    private fun updateStatus(message: String) {
        binding.textStatus.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
