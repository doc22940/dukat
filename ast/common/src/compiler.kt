package org.jetbrains.dukat.ast

import org.jetbrains.dukat.ast.lowerings.lowerPrimitives


fun translateType(declaration: TypeDeclaration): String {
    val res = mutableListOf<String>(declaration.value)
    if (declaration.isGeneric()) {
        val paramsList = mutableListOf<String>()
        for (param in declaration.params) {
            paramsList.add(translateType(param))
        }
        res.add("<" + paramsList.joinToString(", ") + ">")
    }
    return res.joinToString("")
}

fun lowerNativeArray(node: DocumentRoot): DocumentRoot {

    val loweredDeclarations = node.declarations.map { declaration -> when(declaration) {
        is VariableDeclaration -> {
            if (declaration.type.value == "@@ArraySugar") {
                VariableDeclaration(declaration.name, TypeDeclaration("Array", declaration.type.params))
            } else {
                declaration.copy()
            }
        }
        else -> declaration.copy() as Declaration
    }}


    return node.copy(declarations = loweredDeclarations)
}


private fun findNullableType(type: TypeDeclaration): TypeDeclaration? {
    if (type.value != "@@Union") {
        return null
    }

    val params = type.params.filter {
        when (it.value) {
            "undefined" -> false
            "null" -> false
            else -> true
        }
    }

    if (params.size == 1) {
        return params[0]
    } else {
        return null
    }
}


private fun lowerNullableType(node: VariableDeclaration, type: TypeDeclaration) : TypeDeclaration {
    val nullableType = findNullableType(type)

    if (nullableType != null) {
        return TypeDeclaration(
                nullableType.value + "?",
                nullableType.params.map { lowerNullableType(node, it.copy()) }.toTypedArray()
        )
    } else {
        return type.copy()
    }
}

private fun lowerNullable(node: DocumentRoot): DocumentRoot {

    val loweredDeclarations = node.declarations.map { declaration -> when(declaration) {
        is VariableDeclaration -> VariableDeclaration(declaration.name, lowerNullableType(declaration, declaration.type))
        else -> declaration.copy() as Declaration
    }}

    return node.copy(declarations = loweredDeclarations)
}


fun compile(originalTree: AstTree): String {
    var docRoot = lowerNativeArray(originalTree.root)
    docRoot = lowerNullable(docRoot)
    docRoot = lowerPrimitives(docRoot)

    val res = mutableListOf<String>()

    for (child in docRoot.declarations) {
        if (child is VariableDeclaration) {
            val declaration = child
            res.add("export var ${declaration.name}: ${translateType(declaration.type)}")
        } else if (child is FunctionDeclaration) {
            val declaration = child
            val params = declaration.parameters.map { it.name + ": " + translateType(it.type) }.joinToString(", ")
            val returnType = translateType(child.type)
            res.add("external fun ${declaration.name}(${params}): ${returnType} = definedExternally")
        }
    }

    return res.joinToString("\n")
}

fun compile(fileName: String, translator: (fileName: String) -> AstTree): String {
    return compile(translator(fileName))
}