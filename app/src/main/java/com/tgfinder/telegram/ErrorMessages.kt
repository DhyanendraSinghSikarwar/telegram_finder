package com.tgfinder.telegram

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns TDLib / network errors into messages a person can act on. */
object ErrorMessages {

    fun forTelegram(e: Throwable): String {
        if (e is FloodWaitException) return floodWait(e.seconds)
        val msg = e.message.orEmpty()
        return when {
            msg.contains("API_ID_INVALID") || msg.contains("api_id") && msg.contains("invalid", true) ||
                msg.contains("Valid api_id must be provided") ->
                "The API ID or API hash is not valid. Check them in Settings (from my.telegram.org)."
            msg.contains("API_ID_PUBLISHED_FLOOD") -> "This API ID is blocked by Telegram. Create your own at my.telegram.org."
            msg.contains("PHONE_NUMBER_INVALID") -> "That phone number is not valid. Include the country code, e.g. +91 98765 43210."
            msg.contains("PHONE_NUMBER_BANNED") -> "This phone number is banned from Telegram."
            msg.contains("PHONE_NUMBER_FLOOD") -> "Too many login attempts for this number. Try again later."
            msg.contains("PHONE_CODE_INVALID") -> "Wrong code. Check the code and try again."
            msg.contains("PHONE_CODE_EXPIRED") -> "The code has expired. Request a new one."
            msg.contains("PHONE_CODE_EMPTY") -> "Enter the code you received."
            msg.contains("PASSWORD_HASH_INVALID") -> "Wrong two-step verification password."
            msg.contains("EMAIL_CODE_INVALID") -> "Wrong email code."
            msg.contains("EMAIL_INVALID") || msg.contains("EMAIL_ADDRESS_INVALID") -> "That email address is not valid."
            msg.contains("AUTH_RESTART") -> "Telegram asked to restart the login. Enter your phone number again."
            msg.contains("SESSION_REVOKED") || msg.contains("AUTH_KEY_UNREGISTERED") -> "Your session was ended. Log in again."
            msg.contains("CHANNEL_PRIVATE") || msg.contains("CHAT_ACCESS") -> "You no longer have access to this chat."
            msg.contains("FILE_REFERENCE") || msg.contains("FILE_ID_INVALID") || msg.contains("Invalid file identifier") ->
                "This file is no longer available on Telegram."
            msg.contains("MESSAGE_NOT_FOUND") || msg.contains("Message not found") -> "The message with this video was deleted."
            msg.contains("Network") || msg.contains("Connection") || e is UnknownHostException -> NO_INTERNET
            msg.isBlank() -> "Something went wrong talking to Telegram."
            else -> "Telegram error: $msg"
        }
    }

    fun forNetwork(e: Throwable): String = when (e) {
        is UnknownHostException -> NO_INTERNET
        is SocketTimeoutException -> "The connection timed out. Check your internet connection."
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: NO_INTERNET
        else -> e.message ?: "Unknown error"
    }

    fun floodWait(seconds: Int): String {
        val human = if (seconds >= 120) "${(seconds + 59) / 60} minutes" else "$seconds seconds"
        return "Telegram is rate-limiting requests. Please wait $human before searching again."
    }

    const val NO_INTERNET = "No internet connection. Check Wi-Fi or mobile data."
    const val TMDB_KEY_MISSING = "TMDB API key missing. Add it in Settings to show posters, ratings and details."
    const val FILE_UNAVAILABLE = "This video is no longer available on Telegram."
}
