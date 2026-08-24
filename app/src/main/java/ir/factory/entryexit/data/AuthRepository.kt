package ir.factory.entryexit.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Wraps Firebase Auth for email/password sign-in, plus loading each user's role from the
 * "users/{uid}" Firestore document (Firebase Auth alone has no concept of app-specific roles).
 *
 * Account creation policy: the very FIRST account ever created for this project automatically
 * becomes ADMIN (so there's always at least one admin without needing manual Firestore console
 * work). Every account after that defaults to GUARD; an existing admin promotes further admins
 * later by editing that guard's "role" field in the Firestore console (Settings screen also
 * exposes this for convenience once signed in as admin).
 *
 * [context] is only used for [cachePrefs] — the small local fallback described on
 * [restoreSessionIfSignedIn] below.
 */
class AuthRepository(context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val usersCollection = firestore.collection("users")
    private val appContext = context.applicationContext
    private val cachePrefs = appContext.getSharedPreferences("auth_profile_cache", Context.MODE_PRIVATE)

    fun isSignedIn(): Boolean = auth.currentUser != null

    suspend fun signIn(email: String, password: String): Result<UserProfile> {
        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val uid = result.user?.uid ?: return Result.failure(IllegalStateException("ورود ناموفق بود"))
            loadProfile(uid).onSuccess { cacheProfile(it) }
        } catch (e: Exception) {
            Result.failure(mapAuthError(e))
        }
    }

    /** Registers a brand-new guard account. The name is stored for display ("who did this
     *  check-in/out") throughout the app. */
    suspend fun signUp(email: String, password: String, displayName: String): Result<UserProfile> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val uid = result.user?.uid ?: return Result.failure(IllegalStateException("ثبت‌نام ناموفق بود"))

            val isFirstEverUser = usersCollection.limit(1).get().await().isEmpty
            val role = if (isFirstEverUser) UserRole.ADMIN else UserRole.GUARD

            val profile = UserProfile(uid = uid, name = displayName.trim(), email = email.trim(), role = role)
            usersCollection.document(uid).set(
                mapOf(
                    "name" to profile.name,
                    "email" to profile.email,
                    "role" to profile.role.name,
                    "createdAt" to System.currentTimeMillis()
                )
            ).await()

            cacheProfile(profile)
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(mapAuthError(e))
        }
    }

    suspend fun loadProfile(uid: String): Result<UserProfile> {
        return try {
            val doc = usersCollection.document(uid).get().await()
            if (!doc.exists()) {
                return Result.failure(IllegalStateException("پروفایل کاربری یافت نشد؛ با مدیر تماس بگیرید"))
            }
            val profile = UserProfile(
                uid = uid,
                name = doc.getString("name") ?: "",
                email = doc.getString("email") ?: "",
                role = UserRole.fromStringOrDefault(doc.getString("role"))
            )
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(mapAuthError(e))
        }
    }

    /** Called on app start if Firebase already has a signed-in user (e.g. app was reopened),
     *  so we don't force a re-login every time.
     *
     *  Firebase Auth itself remembers the sign-in locally with no network needed — but the
     *  role/name lookup here is a live Firestore read, which used to mean: no internet on
     *  launch => this read fails => the app got stuck showing the login screen even though the
     *  person was, as far as Firebase Auth was concerned, still signed in. To fix that, a
     *  successful [loadProfile] here (or from [signIn]/[signUp]) is also mirrored into
     *  SharedPreferences via [cacheProfile]; if the live read now fails, [loadCachedProfile] is
     *  used instead so the app can still open offline, picking up the latest name/role again the
     *  next time there's a connection. */
    suspend fun restoreSessionIfSignedIn(): Result<UserProfile>? {
        val uid = auth.currentUser?.uid ?: return null
        val liveResult = loadProfile(uid)
        if (liveResult.isSuccess) {
            liveResult.getOrNull()?.let { cacheProfile(it) }
            return liveResult
        }
        val cached = loadCachedProfile(uid)
        return if (cached != null) Result.success(cached) else liveResult
    }

    fun signOut() {
        auth.signOut()
        Session.clear()
        cachePrefs.edit().clear().apply()
    }

    private fun cacheProfile(profile: UserProfile) {
        cachePrefs.edit()
            .putString(KEY_UID, profile.uid)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_ROLE, profile.role.name)
            .apply()
    }

    /** Only returned when the cached [KEY_UID] still matches the currently-signed-in Firebase
     *  Auth user — guards against a stale cache from a previous account on a shared device. */
    private fun loadCachedProfile(uid: String): UserProfile? {
        if (cachePrefs.getString(KEY_UID, null) != uid) return null
        return UserProfile(
            uid = uid,
            name = cachePrefs.getString(KEY_NAME, "") ?: "",
            email = cachePrefs.getString(KEY_EMAIL, "") ?: "",
            role = UserRole.fromStringOrDefault(cachePrefs.getString(KEY_ROLE, null))
        )
    }

    private fun mapAuthError(e: Exception): Exception {
        val message = when {
            e.message?.contains("badly formatted", ignoreCase = true) == true -> "فرمت ایمیل نامعتبر است"
            e.message?.contains("password is invalid", ignoreCase = true) == true -> "رمز عبور اشتباه است"
            e.message?.contains("no user record", ignoreCase = true) == true -> "کاربری با این ایمیل یافت نشد"
            e.message?.contains("email address is already in use", ignoreCase = true) == true -> "این ایمیل قبلاً ثبت شده است"
            e.message?.contains("network", ignoreCase = true) == true -> "اتصال اینترنت برقرار نیست"
            e.message?.contains("at least 6 characters", ignoreCase = true) == true -> "رمز عبور باید حداقل ۶ کاراکتر باشد"
            e.message?.contains("too many", ignoreCase = true) == true ||
                e.message?.contains("try again later", ignoreCase = true) == true ->
                "چند بار پشت‌سرهم تلاش شده؛ چند دقیقه صبر کنید و دوباره امتحان کنید"
            e.message?.contains("json conversion failed", ignoreCase = true) == true ->
                "خطای داخلی گوگل روی این گوشی. لطفاً از Play Store برنامه‌های «Google Play services» و خود «Play Store» را به‌روزرسانی کنید، سپس دوباره امتحان کنید"
            else -> e.message ?: "خطای ورود/ثبت‌نام"
        }
        return IllegalStateException(message)
    }

    private companion object {
        const val KEY_UID = "uid"
        const val KEY_NAME = "name"
        const val KEY_EMAIL = "email"
        const val KEY_ROLE = "role"
    }
}
