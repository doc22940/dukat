package org.jetbrains.dukat.ast.model.nodes

import org.jetbrains.dukat.tsmodel.ParameterDeclaration
import org.jetbrains.dukat.tsmodel.types.ParameterValueDeclaration

data class FunctionTypeNode(
        val parameters: List<ParameterDeclaration>,
        val type: ParameterValueDeclaration,
        override var nullable: Boolean = false,
        override var meta: ParameterValueDeclaration? = null
) : ParameterValueDeclaration