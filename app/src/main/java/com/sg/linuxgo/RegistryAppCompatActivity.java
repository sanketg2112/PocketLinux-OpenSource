package com.sg.linuxgo;

import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * {@link AppCompatActivity} whose {@link #findViewById(int)} consults an optional
 * in-memory view registry first, then the real window hierarchy.
 *
 * <p>Must stay in <strong>Java</strong>. A Kotlin override with non-null return type
 * {@code T} inserts a null-check on return; when the hierarchy has no match (normal for
 * framework / IME / AppCompat lookups of optional ids) that becomes a process-killing
 * {@code NullPointerException: findViewById(...) must not be null} — field Crash A.
 *
 * <p>Platform {@code findViewById} is {@code @Nullable}; callers that need a view must
 * null-check. Registry hits still return the dummy/bridge views used by Compose.
 */
public abstract class RegistryAppCompatActivity extends AppCompatActivity {

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(@IdRes int id) {
        View registered = findRegisteredView(id);
        if (registered != null) {
            return (T) registered;
        }
        return super.findViewById(id);
    }

    /**
     * @param id Android view id
     * @return registry view for {@code id}, or {@code null} to use the real hierarchy
     */
    @Nullable
    protected abstract View findRegisteredView(int id);
}
