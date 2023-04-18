// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.compilerPlugin.parcelize.quickfixes

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.classic.quickfixes.KotlinQuickFixAction
import org.jetbrains.kotlin.idea.compilerPlugin.parcelize.KotlinParcelizeBundle
import org.jetbrains.kotlin.idea.util.findAnnotation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getAllSuperClassifiers
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.TypeUtils

class K1ParcelMigrateToParcelizeQuickFix(clazz: KtClass) : KotlinQuickFixAction<KtClass>(clazz) {
    override fun getText() = KotlinParcelizeBundle.message("parcelize.fix.migrate.to.parceler.companion.object")
    override fun getFamilyName(): String = text

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val clazz = element ?: return
        val applicator = ParcelMigrateToParcelizeQuickFixApplicator(Resolver, clazz.analyze())
        val preparedAction = applicator.prepare(clazz)

        val ktPsiFactory = KtPsiFactory(project, markGenerated = true)
        preparedAction.execute(clazz, ktPsiFactory)
    }

    private object Resolver : ParcelMigrateToParcelizeResolver<BindingContext> {
        private fun KtTypeReference.getFqName(context: BindingContext) =
            context[BindingContext.TYPE, this]?.constructor?.declarationDescriptor?.fqNameSafe

        override fun KtCallableDeclaration.getReturnTypeFqName(context: BindingContext): FqName? =
            typeReference?.getFqName(context)

        override fun KtCallableDeclaration.getReceiverTypeFqName(context: BindingContext): FqName? =
            receiverTypeReference?.getFqName(context)

        override fun KtCallableDeclaration.getOverrideCount(context: BindingContext): Int =
            context[BindingContext.FUNCTION, this]?.overriddenDescriptors?.size ?: 0

        override fun KtDeclaration.hasAnnotation(context: BindingContext, annotationFqName: FqName): Boolean =
            findAnnotation(annotationFqName) != null

        private fun ClassifierDescriptor.hasSuperType(superTypeFqName: FqName): Boolean =
            getAllSuperClassifiers().any { it.fqNameSafe == superTypeFqName }

        override fun KtClassOrObject.hasSuperType(context: BindingContext, superTypeFqName: FqName): Boolean =
            context[BindingContext.CLASS, this]?.hasSuperType(superTypeFqName) ?: false

        override fun KtTypeReference.hasSuperType(context: BindingContext, superTypeFqName: FqName): Boolean =
            context[BindingContext.TYPE, this]?.constructor?.declarationDescriptor?.hasSuperType(superTypeFqName) ?: false

        override fun KtCallExpression.resolveToConstructedClass(context: BindingContext): KtClassOrObject? =
            (getResolvedCall(context)?.resultingDescriptor as? ConstructorDescriptor)
                ?.constructedClass
                ?.source
                ?.getPsi()
                as? KtClassOrObject

        override fun KtExpression.evaluateAsConstantInt(context: BindingContext): Int? =
            context[BindingContext.COMPILE_TIME_VALUE, this]?.getValue(TypeUtils.NO_EXPECTED_TYPE) as? Int
    }

    companion object {
        val FACTORY_FOR_WRITE = factory(::K1ParcelMigrateToParcelizeQuickFix)
        val FACTORY_FOR_CREATOR = factory<KtObjectDeclaration> {
            it.getStrictParentOfType<KtClass>()?.let(::K1ParcelMigrateToParcelizeQuickFix)
        }
    }
}
