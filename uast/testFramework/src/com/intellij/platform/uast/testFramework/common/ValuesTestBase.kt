// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.uast.testFramework.common

import com.intellij.testFramework.assertEqualsToFile
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.evaluation.MapBasedEvaluationContext
import org.jetbrains.uast.evaluation.UEvaluationContext
import org.jetbrains.uast.evaluation.UEvaluatorExtension
import org.jetbrains.uast.evaluation.analyzeAll
import org.jetbrains.uast.visitor.UastVisitor
import java.io.File

interface ValuesTestBase {
  fun getTestDataPath(): String
  fun getEvaluatorExtension(): UEvaluatorExtension? = null

  private fun UFile.analyzeAll() = analyzeAll(extensions = getEvaluatorExtension()?.let { listOf(it) } ?: emptyList())

  private fun UFile.asLogValues(evaluationContext: UEvaluationContext, cachedOnly: Boolean) =
    ValueLogger(evaluationContext, cachedOnly).apply {
      this@asLogValues.accept(this)
    }.toString()

  fun check(testName: String, file: UFile) {
    val valuesFile = File(getTestDataPath(), testName.substringBeforeLast('.') + ".values.txt")

    val evaluationContext = file.analyzeAll()
    assertEqualsToFile("Log values", valuesFile, file.asLogValues(evaluationContext, cachedOnly = false))

    val copyFile = createCopyToCheck(file)
    if (copyFile != null) {
      assertEqualsToFile("Log cached values", valuesFile, copyFile.asLogValues(evaluationContext, cachedOnly = true))
    }
  }

  fun createCopyToCheck(file: UFile): UFile? = null

  class ValueLogger(private val evaluationContext: UEvaluationContext, private val cachedOnly: Boolean) : UastVisitor {
    private val builder = StringBuilder()
    private var level = 0

    override fun visitElement(node: UElement): Boolean {
      val initialLine = node.asLogString() + " [" + run {
        val renderString = node.asRenderString().lines()
        if (renderString.size == 1) {
          renderString.single()
        }
        else {
          renderString.first() + "..." + renderString.last()
        }
      } + "]"

      (1..level).forEach { _ -> builder.append("    ") }
      builder.append(initialLine)
      if (node is UExpression) {
        val value = if (cachedOnly) {
          (evaluationContext as? MapBasedEvaluationContext)?.cachedValueOf(node)
        }
        else {
          evaluationContext.valueOfIfAny(node)
        }
        builder.append(" = ").append(value ?: "NON-EVALUATED")
      }
      builder.appendln()
      level++
      return false
    }

    override fun afterVisitElement(node: UElement) {
      level--
    }

    override fun toString(): String = builder.toString()
  }
}
