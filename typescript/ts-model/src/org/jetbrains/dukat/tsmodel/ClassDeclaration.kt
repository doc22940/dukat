package org.jetbrains.dukat.tsmodel

import org.jetbrains.dukat.astCommon.NameEntity

data class ClassDeclaration(
        override val name: NameEntity,
        override val members: List<MemberDeclaration>,
        override val typeParameters: List<TypeParameterDeclaration>,
        override val parentEntities: List<HeritageClauseDeclaration>,
        val modifiers: List<ModifierDeclaration>,
        override val uid: String
) : ClassLikeDeclaration, ExpressionDeclaration
