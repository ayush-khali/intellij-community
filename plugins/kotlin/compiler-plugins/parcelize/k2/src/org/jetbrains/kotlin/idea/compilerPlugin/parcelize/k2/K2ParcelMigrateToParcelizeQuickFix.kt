// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.compilerPlugin.parcelize.k2

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analysis.api.KtAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.hasAnnotation
import org.jetbrains.kotlin.analysis.api.base.KtConstantValue
import org.jetbrains.kotlin.analysis.api.calls.successfulConstructorCallOrNull
import org.jetbrains.kotlin.analysis.api.calls.symbol
import org.jetbrains.kotlin.analysis.api.components.KtConstantEvaluationMode
import org.jetbrains.kotlin.analysis.api.components.buildClassType
import org.jetbrains.kotlin.analysis.api.lifetime.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KtCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.psi
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.fixes.AbstractKotlinApplicableQuickFix
import org.jetbrains.kotlin.idea.compilerPlugin.parcelize.KotlinParcelizeBundle
import org.jetbrains.kotlin.idea.compilerPlugin.parcelize.quickfixes.ParcelMigrateToParcelizeQuickFixApplicator
import org.jetbrains.kotlin.idea.compilerPlugin.parcelize.quickfixes.ParcelMigrateToParcelizeResolver
import org.jetbrains.kotlin.idea.compilerPlugin.parcelize.quickfixes.factory
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

class K2ParcelMigrateToParcelizeQuickFix(clazz: KtClass) : AbstractKotlinApplicableQuickFix<KtClass>(clazz) {
    override fun getFamilyName() = KotlinParcelizeBundle.message("parcelize.fix.migrate.to.parceler.companion.object")

    @OptIn(KtAllowAnalysisOnEdt::class)
    override fun apply(element: KtClass, project: Project, editor: Editor?, file: KtFile) {
        val preparedAction = allowAnalysisOnEdt {
            analyze(element) {
                ParcelMigrateToParcelizeQuickFixApplicator(Resolver, this@analyze).prepare(element)
            }
        }

        val ktPsiFactory = KtPsiFactory(project, markGenerated = true)
        preparedAction.execute(element, ktPsiFactory)
    }

    private object Resolver : ParcelMigrateToParcelizeResolver<KtAnalysisSession> {
        private fun KtType.getFqName(context: KtAnalysisSession): FqName? = with(context) {
            expandedClassSymbol?.classIdIfNonLocal?.asSingleFqName()
        }
        override fun KtCallableDeclaration.getReturnTypeFqName(context: KtAnalysisSession): FqName? = with(context) {
            (getSymbol() as KtCallableSymbol).returnType.getFqName(context)
        }

        override fun KtCallableDeclaration.getReceiverTypeFqName(context: KtAnalysisSession): FqName? = with(context) {
            (getSymbol() as KtCallableSymbol).receiverType?.getFqName(context)
        }

        override fun KtCallableDeclaration.getOverrideCount(context: KtAnalysisSession): Int = with(context) {
            (getSymbol() as KtCallableSymbol).getAllOverriddenSymbols().size
        }

        override fun KtDeclaration.hasAnnotation(context: KtAnalysisSession, annotationFqName: FqName): Boolean = with(context) {
            getSymbol().hasAnnotation(ClassId.topLevel(annotationFqName))
        }

        override fun KtClassOrObject.hasSuperType(context: KtAnalysisSession, superTypeFqName: FqName): Boolean = with(context) {
            val subClassSymbol = getClassOrObjectSymbol() ?: return false
            val superClassSymbol = getClassOrObjectSymbolByClassId(ClassId.topLevel(superTypeFqName)) ?: return false
            subClassSymbol.isSubClassOf(superClassSymbol)
        }

        override fun KtTypeReference.hasSuperType(context: KtAnalysisSession, superTypeFqName: FqName): Boolean = with(context) {
            val superType = buildClassType(ClassId.topLevel(superTypeFqName))
            val subType = getKtType()
            subType.isSubTypeOf(superType)
        }

        override fun KtCallExpression.resolveToConstructedClass(context: KtAnalysisSession): KtClassOrObject? = with(context) {
            resolveCall()
                .successfulConstructorCallOrNull()
                ?.symbol
                ?.containingClassIdIfNonLocal
                ?.let { getClassOrObjectSymbolByClassId(it) }
                ?.psi as? KtClassOrObject
        }

        override fun KtExpression.evaluateAsConstantInt(context: KtAnalysisSession): Int? = with(context) {
            (evaluate(KtConstantEvaluationMode.CONSTANT_LIKE_EXPRESSION_EVALUATION) as? KtConstantValue.KtIntConstantValue)?.value
        }
    }

    companion object {
        val FACTORY_FOR_WRITE = factory(::K2ParcelMigrateToParcelizeQuickFix)
        val FACTORY_FOR_CREATOR = factory<KtObjectDeclaration> {
            it.getStrictParentOfType<KtClass>()?.let(::K2ParcelMigrateToParcelizeQuickFix)
        }
    }
}
