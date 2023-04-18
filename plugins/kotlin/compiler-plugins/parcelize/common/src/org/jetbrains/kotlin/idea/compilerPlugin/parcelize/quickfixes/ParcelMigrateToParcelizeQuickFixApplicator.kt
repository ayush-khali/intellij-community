// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.compilerPlugin.parcelize.quickfixes

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.core.getOrCreateCompanionObject
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.unwrapBlockOrParenthesis
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.parcelize.ParcelizeNames
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.addRemoveModifier.setModifierList
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.createSmartPointer
import org.jetbrains.kotlin.psi.typeRefHelpers.setReceiverTypeReference

class ParcelMigrateToParcelizeQuickFixApplicator<CONTEXT>(
    private val resolver: ParcelMigrateToParcelizeResolver<CONTEXT>,
    private val resolveContext: CONTEXT,
) {
    companion object {
        private val PARCELER_FQNAME = FqName("kotlinx.parcelize.Parceler")
        private val PARCELER_WRITE_FUNCTION_NAME = Name.identifier("write")
        private val PARCELER_CREATE_FUNCTION_NAME = Name.identifier("create")
        private val LOG = Logger.getInstance(ParcelMigrateToParcelizeQuickFixApplicator::class.java)
    }

    private fun KtClass.findParcelerCompanionObject(): KtObjectDeclaration? = with(resolver) {
        companionObjects.firstOrNull { it.hasSuperType(resolveContext, PARCELER_FQNAME) }
    }

    private fun KtNamedFunction.doesLookLikeWriteToParcelOverride(): Boolean = with(resolver) {
        return name == "writeToParcel"
                && hasModifier(KtTokens.OVERRIDE_KEYWORD)
                && receiverTypeReference == null
                && valueParameters.size == 2
                && typeParameters.size == 0
                && valueParameters[0].getReturnTypeFqName(resolveContext) == ParcelizeNames.PARCEL_ID.asSingleFqName()
                && valueParameters[1].getReturnTypeFqName(resolveContext) == StandardNames.FqNames._int.toSafe()
    }

    private fun KtNamedFunction.doesLookLikeNewArrayOverride(): Boolean = with(resolver) {
        return name == "newArray"
                && hasModifier(KtTokens.OVERRIDE_KEYWORD)
                && receiverTypeReference == null
                && valueParameters.size == 1
                && typeParameters.size == 0
                && valueParameters[0].getReturnTypeFqName(resolveContext) == StandardNames.FqNames._int.toSafe()
    }

    private fun KtNamedFunction.doesLookLikeDescribeContentsOverride(): Boolean = with(resolver) {
        return name == "describeContents"
                && hasModifier(KtTokens.OVERRIDE_KEYWORD)
                && receiverTypeReference == null
                && valueParameters.size == 0
                && typeParameters.size == 0
                && getReturnTypeFqName(resolveContext) == StandardNames.FqNames._int.toSafe()
    }

    private fun KtClass.findWriteToParcelOverride() = findFunction { doesLookLikeWriteToParcelOverride() }
    private fun KtClass.findDescribeContentsOverride() = findFunction { doesLookLikeDescribeContentsOverride() }
    private fun KtObjectDeclaration.findNewArrayOverride() = findFunction { doesLookLikeNewArrayOverride() }

    private fun KtClass.findCreatorClass(): KtClassOrObject? = with(resolver) {
        for (companion in companionObjects) {
            if (companion.name == "CREATOR") {
                return companion
            }

            val creatorProperty = companion.declarations.asSequence()
                .filterIsInstance<KtProperty>()
                .firstOrNull { it.name == "CREATOR" }
                ?: continue

            if (!creatorProperty.hasAnnotation(resolveContext, JvmAbi.JVM_FIELD_ANNOTATION_FQ_NAME)) continue

            val initializer = creatorProperty.initializer ?: continue
            when (initializer) {
                is KtObjectLiteralExpression -> return initializer.objectDeclaration
                is KtCallExpression -> initializer.resolveToConstructedClass(resolveContext)?.let { return it }
            }
        }

        return null
    }

    private fun KtNamedFunction.doesLookLikeCreateFromParcelOverride(): Boolean = with(resolver) {
        return name == "createFromParcel"
                && hasModifier(KtTokens.OVERRIDE_KEYWORD)
                && receiverTypeReference == null
                && valueParameters.size == 1
                && typeParameters.size == 0
                && valueParameters[0].getReturnTypeFqName(resolveContext) == ParcelizeNames.PARCEL_ID.asSingleFqName()
    }

    private fun findCreateFromParcel(creator: KtClassOrObject) = creator.findFunction { doesLookLikeCreateFromParcelOverride() }

    private fun KtNamedFunction.doesLookLikeWriteImplementation(): Boolean = with(resolver) {
        val containingParcelableClassFqName = containingClassOrObject?.containingClass()?.fqName ?: return false

        return name == PARCELER_WRITE_FUNCTION_NAME.asString()
                && hasModifier(KtTokens.OVERRIDE_KEYWORD)
                && getReceiverTypeFqName(resolveContext) == containingParcelableClassFqName
                && valueParameters.size == 2
                && typeParameters.size == 0
                && valueParameters[0].getReturnTypeFqName(resolveContext) == ParcelizeNames.PARCEL_ID.asSingleFqName()
                && valueParameters[1].getReturnTypeFqName(resolveContext) == StandardNames.FqNames._int.toSafe()
    }

    private fun KtNamedFunction.doesLookLikeCreateImplementation(): Boolean = with(resolver) {
        return name == PARCELER_CREATE_FUNCTION_NAME.asString()
                && hasModifier(KtTokens.OVERRIDE_KEYWORD)
                && receiverTypeReference == null
                && valueParameters.size == 1
                && typeParameters.size == 0
                && valueParameters[0].getReturnTypeFqName(resolveContext) == ParcelizeNames.PARCEL_ID.asSingleFqName()
    }

    private fun KtObjectDeclaration.findCreateImplementation() = findFunction { doesLookLikeCreateImplementation() }
    private fun KtObjectDeclaration.findWriteImplementation() = findFunction { doesLookLikeWriteImplementation() }

    private fun KtClassOrObject.findFunction(f: KtNamedFunction.() -> Boolean) =
        declarations.asSequence().filterIsInstance<KtNamedFunction>().firstOrNull(f)

    fun prepare(parcelableClass: KtClass): PreparedAction = with(resolver) {
        val parcelerObject = parcelableClass.findParcelerCompanionObject()
        val parcelerOrCompanion = parcelerObject ?: parcelableClass.companionObjects.firstOrNull()

        val oldWriteToParcelFunction = parcelableClass.findWriteToParcelOverride()
        val oldCreateFromParcelFunction = parcelableClass.findCreatorClass()?.let { findCreateFromParcel(it) }

        val shouldAddParcelerSupertype = parcelerObject?.hasSuperType(resolveContext, PARCELER_FQNAME) != true
        val parcelerSupertypeEntriesToRemove = parcelerOrCompanion?.superTypeListEntries?.mapNotNull {
            if (it.typeReference?.hasSuperType(resolveContext, ParcelizeNames.CREATOR_FQN) == true) {
                it.createSmartPointer()
            } else {
                null
            }
        } ?: emptyList()

        val parcelerCreatorPropertiesToRemove =
            parcelerOrCompanion?.declarations?.asSequence()
                ?.filterIsInstance<KtProperty>()
                ?.filter {
                    it.name == ParcelizeNames.CREATOR_NAME.asString()
                            && it.hasAnnotation(resolveContext, JvmAbi.JVM_FIELD_ANNOTATION_FQ_NAME)
                }
                ?.map { it.createSmartPointer() }
                ?.toList() ?: emptyList()

        val describeContentsFunctionToRemove = parcelableClass.findDescribeContentsOverride()?.takeIf {
            val returnExpr = it.bodyExpression?.unwrapBlockOrParenthesis()
            return@takeIf (
                    returnExpr is KtReturnExpression
                            && returnExpr.getTargetLabel() == null
                            && returnExpr.returnedExpression?.evaluateAsConstantInt(resolveContext) == 0
                            && it.getOverrideCount(resolveContext) == 1)
        }

        return PreparedAction(
            parcelerObject = parcelerObject?.createSmartPointer(),
            parcelerWriteFunction = parcelerObject?.findWriteImplementation()?.createSmartPointer(),
            parcelerCreateFunction = parcelerObject?.findCreateImplementation()?.createSmartPointer(),
            parcelerNewArrayFunction = parcelerOrCompanion?.findNewArrayOverride()?.createSmartPointer(),

            writeToParcelFunction = oldWriteToParcelFunction?.createSmartPointer(),
            createFromParcelFunction = oldCreateFromParcelFunction?.createSmartPointer(),

            shouldAddParcelerSupertype = shouldAddParcelerSupertype,
            parcelerSupertypesToRemove = parcelerSupertypeEntriesToRemove,
            parcelerCreatorPropertiesToRemove = parcelerCreatorPropertiesToRemove,
            describeContentsFunctionToRemove = describeContentsFunctionToRemove?.createSmartPointer()
        )
    }

    data class PreparedAction(
        val parcelerObject: SmartPsiElementPointer<KtObjectDeclaration>?,
        val parcelerWriteFunction: SmartPsiElementPointer<KtNamedFunction>?,
        val parcelerCreateFunction: SmartPsiElementPointer<KtNamedFunction>?,
        val parcelerNewArrayFunction: SmartPsiElementPointer<KtNamedFunction>?,

        val writeToParcelFunction: SmartPsiElementPointer<KtNamedFunction>?,
        val createFromParcelFunction: SmartPsiElementPointer<KtNamedFunction>?,

        val shouldAddParcelerSupertype: Boolean,
        val parcelerSupertypesToRemove: List<SmartPsiElementPointer<KtSuperTypeListEntry>>,
        val parcelerCreatorPropertiesToRemove: List<SmartPsiElementPointer<KtProperty>>,
        val describeContentsFunctionToRemove: SmartPsiElementPointer<KtNamedFunction>?,
    ) {

        fun execute(parcelableClass: KtClass, ktPsiFactory: KtPsiFactory) {
            val parcelerObject = this.parcelerObject?.element ?: parcelableClass.getOrCreateCompanionObject()

            val parcelerTypeArg = parcelableClass.name ?: run {
                LOG.error("Parceler class should not be an anonymous class")
                return
            }

            if (shouldAddParcelerSupertype) {
                val entryText = "${PARCELER_FQNAME.asString()}<$parcelerTypeArg>"
                parcelerObject.addSuperTypeListEntry(ktPsiFactory.createSuperTypeEntry(entryText)).shortenReferences()
            }

            parcelerSupertypesToRemove.mapNotNull { it.element }.forEach { parcelerObject.removeSuperTypeListEntry(it) }

            if (parcelerObject.name == ParcelizeNames.CREATOR_NAME.asString()) {
                parcelerObject.nameIdentifier?.delete()
            }

            if (writeToParcelFunction != null) {
                parcelerWriteFunction?.element?.delete() // Remove old implementation

                val oldFunction = writeToParcelFunction.element ?: return
                val newFunction = oldFunction.copy() as KtFunction
                oldFunction.delete()

                newFunction.setName(PARCELER_WRITE_FUNCTION_NAME.asString())
                newFunction.setModifierList(ktPsiFactory.createModifierList(KtTokens.OVERRIDE_KEYWORD))
                newFunction.setReceiverTypeReference(ktPsiFactory.createType(parcelerTypeArg))
                newFunction.valueParameterList?.apply {
                    assert(parameters.size == 2)
                    val parcelParameterName = parameters[0].name ?: "parcel"
                    val flagsParameterName = parameters[1].name ?: "flags"

                    repeat(parameters.size) { removeParameter(0) }
                    addParameter(ktPsiFactory.createParameter("$parcelParameterName : ${ParcelizeNames.PARCEL_ID.asFqNameString()}"))
                    addParameter(ktPsiFactory.createParameter("$flagsParameterName : Int"))
                }

                parcelerObject.addDeclaration(newFunction).valueParameterList?.shortenReferences()
            } else if (parcelerWriteFunction == null) {
                val writeFunction = "fun $parcelerTypeArg.write(parcel: ${ParcelizeNames.PARCEL_ID.asFqNameString()}, flags: Int) = TODO()"
                parcelerObject.addDeclaration(ktPsiFactory.createFunction(writeFunction)).valueParameterList?.shortenReferences()
            }

            if (createFromParcelFunction != null) {
                parcelerCreateFunction?.element?.delete() // Remove old implementation

                val oldFunction = createFromParcelFunction.element ?: return
                val newFunction = oldFunction.copy() as KtFunction
                if (oldFunction.containingClassOrObject == parcelerObject) {
                    oldFunction.delete()
                }

                newFunction.setName(PARCELER_CREATE_FUNCTION_NAME.asString())
                newFunction.setModifierList(ktPsiFactory.createModifierList(KtTokens.OVERRIDE_KEYWORD))
                newFunction.setReceiverTypeReference(null)
                newFunction.valueParameterList?.apply {
                    assert(parameters.size == 1)
                    val parcelParameterName = parameters[0].name ?: "parcel"

                    removeParameter(0)
                    addParameter(ktPsiFactory.createParameter("$parcelParameterName : ${ParcelizeNames.PARCEL_ID.asFqNameString()}"))
                }

                parcelerObject.addDeclaration(newFunction).valueParameterList?.shortenReferences()
            } else if (parcelerCreateFunction == null) {
                val createFunction = "override fun create(parcel: ${ParcelizeNames.PARCEL_ID.asFqNameString()}): $parcelerTypeArg = TODO()"
                parcelerObject.addDeclaration(ktPsiFactory.createFunction(createFunction)).valueParameterList?.shortenReferences()
            }

            // Always use the default newArray() implementation
            parcelerNewArrayFunction?.element?.delete()

            // Remove describeContents() if it's the default implementation.
            describeContentsFunctionToRemove?.element?.delete()

            parcelerCreatorPropertiesToRemove.forEach { it.element?.delete() }
        }
    }
}

interface ParcelMigrateToParcelizeResolver<CONTEXT> {
    fun KtCallableDeclaration.getReturnTypeFqName(context: CONTEXT): FqName?

    fun KtCallableDeclaration.getReceiverTypeFqName(context: CONTEXT): FqName?

    fun KtCallableDeclaration.getOverrideCount(context: CONTEXT): Int

    fun KtDeclaration.hasAnnotation(context: CONTEXT, annotationFqName: FqName): Boolean

    fun KtClassOrObject.hasSuperType(context: CONTEXT, superTypeFqName: FqName): Boolean

    fun KtTypeReference.hasSuperType(context: CONTEXT, superTypeFqName: FqName): Boolean

    fun KtCallExpression.resolveToConstructedClass(context: CONTEXT): KtClassOrObject?

    fun KtExpression.evaluateAsConstantInt(context: CONTEXT): Int?
}
