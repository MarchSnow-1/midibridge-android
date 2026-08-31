package com.marchsnow.midibridge.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A lifecycle-aware observable that emits each value at most ONCE to observers,
 * avoiding LiveData's sticky-replay behaviour (AND-V3).
 *
 * Problem with plain MutableLiveData for one-shot UI events (Toasts,
 * validation errors): every new observer — including one created by a
 * configuration change (rotation) — immediately receives the LAST event
 * again, so e.g. a "Settings saved" Toast re-appears after rotation.
 *
 * Solution (classic SingleLiveEvent pattern): a consumed flag guarantees
 * each value is dispatched to observers at most once.
 *
 * Limitation: effectively supports a single active observer at a time
 * (sufficient for this app's single-Activity UI).
 */
class SingleLiveEvent<T> : MutableLiveData<T>() {

    private val pending = AtomicBoolean(false)

    override fun setValue(value: T?) {
        pending.set(true)
        super.setValue(value)
    }

    override fun postValue(value: T?) {
        pending.set(true)
        super.postValue(value)
    }

    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        super.observe(owner) { t ->
            // Only propagate if a new value was set since the last dispatch
            if (pending.compareAndSet(true, false)) {
                observer.onChanged(t)
            }
        }
    }

    override fun observeForever(observer: Observer<in T>) {
        super.observeForever { t ->
            if (pending.compareAndSet(true, false)) {
                observer.onChanged(t)
            }
        }
    }
}
