package com.asitsounds.android.db

data class ProjectEntity(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class FileStatus { pending, processing, done, error }

data class MediaFileEntity(
    val id: String,
    val projectId: String,
    val path: String,
    val displayName: String,
    val mime: String?,
    val durationMs: Long?,
    val status: FileStatus,
    val error: String?,
    val createdAt: Long,
)

data class SegmentEntity(
    val id: String,
    val fileId: String,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val textEn: String,
    val textHangul: String?,
    val textKo: String?,
)
