package com.fitscroll.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.fitscroll.app.data.BankRepository
import com.fitscroll.app.data.EarnOutcome
import com.fitscroll.app.data.SettingsRepository
import com.fitscroll.app.pose.Coaching
import com.fitscroll.app.pose.PoseResult
import com.fitscroll.app.pose.PushUpCounter
import com.fitscroll.app.pose.RepPhase
import com.fitscroll.app.pose.SkeletonFrame
import com.fitscroll.app.pose.StrictnessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WorkoutUiState(
    val reps: Int = 0,
    val coaching: Coaching = Coaching.FINDING_YOU,
    val depth: Float = 0f,
    val formOk: Boolean = true,
    val rejection: String? = null,
    val skeleton: SkeletonFrame? = null,
    val phase: RepPhase = RepPhase.SEARCHING,
    val strictness: StrictnessProfile = StrictnessProfile.forLevel(3),
    val useFrontCamera: Boolean = true,
) {
    val tracking: Boolean get() = phase != RepPhase.SEARCHING
}

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository.get(application)
    private val bank = BankRepository.get(application)

    private val counter = PushUpCounter(StrictnessProfile.forLevel(settings.current.strictness))

    private val _state = MutableStateFlow(
        WorkoutUiState(strictness = StrictnessProfile.forLevel(settings.current.strictness)),
    )
    val state: StateFlow<WorkoutUiState> = _state.asStateFlow()

    /**
     * Called from the camera analysis thread for every frame.
     *
     * Rep timing uses elapsedRealtime rather than wall-clock time. A user
     * crossing a timezone or an NTP correction mid-set would otherwise shift
     * every duration, and the minimum-rep-duration gate is precisely what stops
     * a fast wave counting as a push-up.
     */
    fun onPoseResult(result: PoseResult) {
        // Picking up a strictness change made in Settings while the camera is
        // open; the counter abandons the rep in flight when the level moves.
        counter.setProfile(StrictnessProfile.forLevel(settings.current.strictness))

        val update = counter.onFrame(result.metrics, SystemClock.elapsedRealtime())

        _state.value = _state.value.copy(
            reps = update.reps,
            coaching = update.coaching,
            depth = update.depth,
            formOk = update.formOk,
            rejection = update.rejection,
            skeleton = result.skeleton,
            phase = update.phase,
            strictness = StrictnessProfile.forLevel(settings.current.strictness),
        )
    }

    fun flipCamera() {
        _state.value = _state.value.copy(useFrontCamera = !_state.value.useFrontCamera)
    }

    /**
     * Commits the set to the bank and clears the counter.
     *
     * Reps are banked in one transaction at the end rather than credited as
     * they happen, so an abandoned set is not half-paid and the cap overflow
     * can be reported as a single honest number.
     */
    fun bankSet(): EarnOutcome {
        val outcome = bank.earn(_state.value.reps, settings.current.bankCapMinutes)
        counter.reset()
        _state.value = _state.value.copy(reps = 0, depth = 0f, rejection = null)
        return outcome
    }

    fun discardSet() {
        counter.reset()
        _state.value = _state.value.copy(reps = 0, depth = 0f, rejection = null)
    }
}
