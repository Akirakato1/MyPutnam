package com.putnamworkbook.offline.freeworkspace

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

enum class FreeWorkspaceNameError {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
    DUPLICATE,
}

class FreeWorkspaceNameException(
    val error: FreeWorkspaceNameError,
) : IllegalArgumentException(error.name)

data class ValidatedWorkspaceName(
    val display: String,
    val uniquenessKey: String,
)

fun validateWorkspaceName(
    candidate: String,
    existing: Collection<FreeWorkspaceRecord>,
    selfId: UUID? = null,
): Result<ValidatedWorkspaceName> = runCatching {
    val display = candidate.trim()
    when {
        display.isBlank() -> throw FreeWorkspaceNameException(FreeWorkspaceNameError.BLANK)
        display.codePoints().anyMatch(Character::isISOControl) ->
            throw FreeWorkspaceNameException(FreeWorkspaceNameError.CONTROL_CHARACTER)
        display.codePointCount(0, display.length) > MAX_WORKSPACE_NAME_CODE_POINTS ->
            throw FreeWorkspaceNameException(FreeWorkspaceNameError.TOO_LONG)
    }

    val uniquenessKey = workspaceNameUniquenessKey(display)
    if (existing.any { it.id != selfId && workspaceNameUniquenessKey(it.name) == uniquenessKey }) {
        throw FreeWorkspaceNameException(FreeWorkspaceNameError.DUPLICATE)
    }
    ValidatedWorkspaceName(display, uniquenessKey)
}

internal fun workspaceNameUniquenessKey(name: String): String =
    Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

private const val MAX_WORKSPACE_NAME_CODE_POINTS = 80
