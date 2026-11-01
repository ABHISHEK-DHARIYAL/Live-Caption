package com.clearcap.models
import java.io.File
object Untar { fun extract(archive: File, into: File, wanted: Set<String>, active: () -> Boolean) {} }
