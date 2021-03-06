package org.jetrbains.dukat.nodeLowering.lowerings

import org.jetbrains.dukat.ast.model.TypeParameterNode
import org.jetbrains.dukat.ast.model.nodes.*
import org.jetbrains.dukat.ast.model.nodes.export.ExportQualifier
import org.jetbrains.dukat.ast.model.nodes.export.JsDefault
import org.jetbrains.dukat.ast.model.nodes.export.JsModule
import org.jetbrains.dukat.ast.model.nodes.metadata.IntersectionMetadata
import org.jetbrains.dukat.ast.model.nodes.metadata.MuteMetadata
import org.jetbrains.dukat.ast.model.nodes.metadata.ThisTypeInGeneratedInterfaceMetaData
import org.jetbrains.dukat.astCommon.*
import org.jetbrains.dukat.astModel.*
import org.jetbrains.dukat.astModel.modifiers.VisibilityModifierModel
import org.jetbrains.dukat.astModel.statements.AssignmentStatementModel
import org.jetbrains.dukat.astModel.statements.ChainCallModel
import org.jetbrains.dukat.astModel.statements.ReturnStatementModel
import org.jetbrains.dukat.astModel.statements.StatementCallModel
import org.jetbrains.dukat.astModel.statements.StatementModel
import org.jetbrains.dukat.logger.Logging
import org.jetbrains.dukat.panic.raiseConcern
import org.jetbrains.dukat.stdlib.KotlinStdlibEntities
import org.jetbrains.dukat.translatorString.translate
import org.jetbrains.dukat.tsmodel.types.ParameterValueDeclaration
import org.jetbrains.dukat.tsmodel.types.StringLiteralDeclaration
import org.jetrbains.dukat.nodeLowering.NodeTypeLowering
import java.io.File

private val logger = Logging.logger("introduceModels")

private enum class MetaDataOptions {
    SKIP_NULLS
}

private fun MemberNode.isStatic() = when (this) {
    is MethodNode -> static
    is PropertyNode -> static
    else -> false
}


private enum class TranslationContext {
    TYPE_CONSTRAINT,
    IRRELEVANT,
    FUNCTION_TYPE,
    INLINE_EXTENSION,
    CONSTRUCTOR
}

private data class Members(
        val dynamic: List<MemberModel>,
        val static: List<MemberModel>
)

private typealias UidMapper = Map<String, FqNode>
private typealias UidMutableMapper = MutableMap<String, FqNode>

data class FqNode(val node: Entity, val fqName: NameEntity)

private class DocumentConverter(private val documentRootNode: DocumentRootNode, private val uidToNameMapper: UidMapper) {
    private val imports = mutableListOf<ImportModel>()

    companion object {
        val IMPOSSIBLE_CONSTRAINT = IdentifierEntity("<IMPOSSIBLE-CONSTRAINT>")
    }

    @Suppress("UNCHECKED_CAST")
    fun convert(sourceFileName: String, generated: MutableList<SourceFileModel>): ModuleModel {
        val (roots, topDeclarations) = documentRootNode.declarations.partition { it is DocumentRootNode }

        val declarationsMapped = (roots as List<DocumentRootNode>).map { DocumentConverter(it, uidToNameMapper).convert(sourceFileName, generated) } + topDeclarations.mapNotNull { declaration ->
            declaration.convertToModel()
        }

        val declarationsFiltered = mutableListOf<TopLevelModel>()
        val submodules = mutableListOf<ModuleModel>()
        declarationsMapped.forEach { declaration ->
            if (declaration is ModuleModel) submodules.add(declaration) else declarationsFiltered.add(declaration)
        }

        val annotations = mutableListOf<AnnotationModel>()

        documentRootNode.jsModule?.let {
            annotations.add(AnnotationModel("file:JsModule", listOf(it)))
            annotations.add(AnnotationModel("file:JsNonModule", emptyList()))
        }

        documentRootNode.jsQualifier?.let {
            annotations.add(AnnotationModel("file:JsQualifier", listOf(it)))
        }

        return ModuleModel(
                name = documentRootNode.qualifiedPackageName,
                shortName = documentRootNode.qualifiedPackageName.rightMost(),
                declarations = declarationsFiltered,
                annotations = annotations,
                submodules = submodules,
                imports = imports,
                comment = null
        )
    }

    private fun ReferenceEntity.getFqName(ownerName: NameEntity): NameEntity? {
        return if (uid.startsWith("lib-")) {
            IdentifierEntity("<LIBROOT>").appendLeft(ownerName)
        } else {
            uidToNameMapper[uid]?.fqName
        }
    }

    private fun TypeValueNode.getFqName(): NameEntity? {
        return typeReference?.getFqName(value) ?: if (KotlinStdlibEntities.contains(value)) {
            IdentifierEntity("<LIBROOT>").appendLeft(value)
        } else null
    }

    private fun split(members: List<MemberNode>): Members {
        val staticMembers = mutableListOf<MemberModel>()
        val ownMembers = mutableListOf<MemberModel>()

        members.forEach { member ->
            val memberProcessed = member.process()
            if (memberProcessed != null) {
                if (member.isStatic()) {
                    staticMembers.add(memberProcessed)
                } else ownMembers.add(memberProcessed)
            }
        }

        return Members(ownMembers, staticMembers)
    }
    private fun TranslationContext.resolveAsMetaOptions(): Set<MetaDataOptions> {
        return if (this == TranslationContext.FUNCTION_TYPE) {
            setOf()
        } else {
            setOf(MetaDataOptions.SKIP_NULLS)
        }
    }

    private fun NameEntity.addLibPrefix() = IdentifierEntity("<LIBROOT>").appendLeft(this)
    private fun UnionTypeNode.canBeTranslatedAsString(): Boolean {
        return params.all { (it is TypeValueNode) && (it.value == IdentifierEntity("String")) }
    }

    private fun UnionTypeNode.convertMeta(): String {
        return params.joinToString(" | ") { unionMember ->
            if (unionMember.meta is StringLiteralDeclaration) {
                (unionMember.meta as StringLiteralDeclaration).token
            } else {
                unionMember.process().translate()
            }
        }
    }

    private fun HeritageNode.convertToModel(): HeritageModel {
        val isNamedImport = reference?.origin == ReferenceOriginNode.NAMED_IMPORT
        if (isNamedImport) {
            reference?.getFqName(name)?.let {resolvedName ->
                imports.add(ImportModel(resolvedName, name.rightMost()))
            }
        }

        val fqName = if (isNamedImport) {name} else { reference?.getFqName(name) }
        return HeritageModel(
                value = TypeValueModel(name, emptyList(), null, fqName),
                typeParams = typeArguments.map { typeArgument -> typeArgument.process() },
                delegateTo = null
        )
    }

    private fun ParameterValueDeclaration.process(context: TranslationContext = TranslationContext.IRRELEVANT): TypeModel {
        val dynamicName = when (context) {
            TranslationContext.TYPE_CONSTRAINT -> IMPOSSIBLE_CONSTRAINT
            else -> IdentifierEntity("dynamic")
        }
        return when (this) {
            is UnionTypeNode -> if (canBeTranslatedAsString()) {
                val stringEntity = IdentifierEntity("String")
                TypeValueModel(
                        stringEntity,
                        emptyList(),
                        convertMeta(),
                        stringEntity.addLibPrefix()
                )
            } else {
                TypeValueModel(
                        dynamicName,
                        emptyList(),
                        convertMeta(),
                        null
                )
            }
            is TupleTypeNode -> TypeValueModel(
                    dynamicName,
                    emptyList(),
                    "JsTuple<${params.map { it.process().translate() }.joinToString(", ")}>",
                    null
            )
            is TypeParameterNode -> {
                TypeParameterReferenceModel(
                        name = name,
                        metaDescription = meta.processMeta(nullable, context.resolveAsMetaOptions()),
                        nullable = nullable
                )
            }
            is TypeValueNode -> {
                if ((value == IdentifierEntity("String")) && (meta is StringLiteralDeclaration)) {
                    TypeValueModel(value, emptyList(), (meta as StringLiteralDeclaration).token, typeReference?.getFqName(value))
                } else {
                    TypeValueModel(
                            value,
                            params.map { param -> param.process() }.map { TypeParameterModel(it, listOf()) },
                            meta.processMeta(nullable, context.resolveAsMetaOptions()),
                            getFqName(),
                            nullable
                    )
                }
            }
            is FunctionTypeNode -> {
                FunctionTypeModel(
                        parameters = (parameters.map { param ->
                            param.process(TranslationContext.FUNCTION_TYPE)
                        }),
                        type = type.process(TranslationContext.FUNCTION_TYPE),
                        metaDescription = meta.processMeta(nullable, context.resolveAsMetaOptions()),
                        nullable = nullable
                )
            }
            is GeneratedInterfaceReferenceNode -> {
                TypeValueModel(
                        name,
                        typeParameters.map { typeParam -> TypeValueModel(
                                typeParam.name,
                                emptyList(),
                                null,
                                if (reference?.uid?.endsWith("_GENERATED") == true) {
                                    null
                                } else {
                                    reference?.getFqName(typeParam.name)
                                })
                        }.map { TypeParameterModel(it, listOf()) },
                        meta?.processMeta(nullable, setOf(MetaDataOptions.SKIP_NULLS)),
                        reference?.getFqName(name),
                        nullable
                )

            }
            else -> raiseConcern("unable to process ParameterValueDeclaration ${this}") {
                TypeValueModel(
                        IdentifierEntity("dynamic"),
                        emptyList(),
                        null,
                        null,
                        false
                )
            }
        }
    }

    private fun MethodNode.resolveAnnotations(): List<AnnotationModel> {
        if (operator) {
            return when (name) {
                "get" -> listOf(AnnotationModel("nativeGetter", emptyList()))
                "set" -> listOf(AnnotationModel("nativeSetter", emptyList()))
                "invoke" -> listOf(AnnotationModel("nativeInvoke", emptyList()))
                else -> emptyList()
            }
        }

        return emptyList()
    }

    private fun MemberNode.process(): MemberModel? {
        // TODO: how ClassModel end up here?
        return when (this) {
            is ConstructorNode -> ConstructorModel(
                    parameters = parameters.map { param -> param.process(TranslationContext.CONSTRUCTOR) },
                    typeParameters = convertTypeParams(typeParameters),
                    generated = generated
            )
            is MethodNode -> process()
            is PropertyNode -> PropertyModel(
                    name = IdentifierEntity(name),
                    type = type.process(TranslationContext.IRRELEVANT),
                    typeParameters = convertTypeParams(typeParameters),
                    static = static,
                    override = null,
                    immutable = getter && !setter,
                    getter = false,
                    setter = false,
                    open = open
            )
            else -> raiseConcern("unprocessed MemberNode: ${this}") { null }
        }
    }

    private fun ParameterNode.process(context: TranslationContext = TranslationContext.IRRELEVANT): ParameterModel {
        return ParameterModel(
                type = type.process(context),
                name = name,
                initializer = when (context) {
                    TranslationContext.CONSTRUCTOR -> {
                        null
                    }
                    TranslationContext.INLINE_EXTENSION -> {
                        if (optional) {
                            StatementCallModel(IdentifierEntity("null"), null, emptyList(), meta)
                        } else {
                            null
                        }
                    }
                    else -> {
                        when {
                            initializer != null -> StatementCallModel(initializer!!.value, null, emptyList(), meta)
                            optional -> StatementCallModel(IdentifierEntity("definedExternally"), null, emptyList(), meta)
                            else -> null
                        }
                    }
                },
                vararg = vararg,
                modifier = null
        )
    }

    private fun ParameterValueDeclaration?.processMeta(ownerIsNullable: Boolean, metadataOptions: Set<MetaDataOptions> = emptySet()): String? {
        return when (this) {
            is ThisTypeInGeneratedInterfaceMetaData -> "this"
            is IntersectionMetadata -> params.map {
                it.process().translate()
            }.joinToString(" & ")
            else -> {
                if (!metadataOptions.contains(MetaDataOptions.SKIP_NULLS)) {
                    val skipNullableAnnotation = this is MuteMetadata
                    if (ownerIsNullable && !skipNullableAnnotation) {
                        //TODO: consider rethinking this restriction
                        return "= null"
                    } else null
                } else null
            }
        }
    }

    private fun ExportQualifier?.toAnnotation(): MutableList<AnnotationModel> {
        return when (this) {
            is JsModule -> mutableListOf(AnnotationModel("JsModule", listOf(name)))
            is JsDefault -> mutableListOf(AnnotationModel("JsName", listOf(IdentifierEntity("default"))))
            else -> mutableListOf()
        }
    }

    private fun ClassLikeReferenceNode?.convert(): ClassLikeReferenceModel? {
        return this?.let { extendNode ->
            ClassLikeReferenceModel(uidToNameMapper[extendNode.uid]?.fqName
                    ?: extendNode.name, extendNode.typeParameters)
        }
    }

    private fun convertParentEntities(parentEntities: List<HeritageNode>, generatedMethodsHandler: ((methods: List<MethodModel>) -> Unit)? = null): List<HeritageModel> {
        return parentEntities.map { parentEntity ->
            val node = uidToNameMapper[parentEntity.reference?.uid]?.node
            if (node is InterfaceNode) {
                val generatedMethods = node.members.filterIsInstance(MethodNode::class.java).filter { it.meta?.generated == true }
                if (!generatedMethods.isEmpty()) {
                    generatedMethodsHandler?.invoke(generatedMethods.map { it.copy() }.map { it.process(uidToNameMapper[node.uid]?.fqName) })
                }
            }
            parentEntity.convertToModel()
        }
    }

    private fun VariableNode.resolveGetter(): StatementModel? {
        return if (inline) {
            ChainCallModel(
                    StatementCallModel(
                            QualifierEntity(IdentifierEntity("this"), IdentifierEntity("asDynamic")), emptyList()
                    ),
                    StatementCallModel(name.rightMost(), null)
            )
        } else null
    }

    private fun VariableNode.resolveSetter(): StatementModel? {
        return if (inline) {
            AssignmentStatementModel(
                    ChainCallModel(
                            StatementCallModel(QualifierEntity(IdentifierEntity("this"), IdentifierEntity("asDynamic")), emptyList()),
                            StatementCallModel(name.rightMost(), null)
                    ),
                    StatementCallModel(IdentifierEntity("value"), null)
            )
        } else null
    }

    private fun ParameterValueDeclaration.isUnit(): Boolean {
        return (this is TypeValueNode) && (value == IdentifierEntity("Unit"))
    }

    private fun FunctionNode.resolveBody(): List<StatementModel> {
        return when (val nodeContext = this.context) {
            is IndexSignatureGetter -> listOf(
                    ReturnStatementModel(
                            ChainCallModel(
                                    StatementCallModel(QualifierEntity(IdentifierEntity("this"), IdentifierEntity("asDynamic")), emptyList()),
                                    StatementCallModel(IdentifierEntity("get"), listOf(
                                            IdentifierEntity(nodeContext.name)
                                    ))
                            )
                    )
            )

            is IndexSignatureSetter -> listOf(ChainCallModel(
                    StatementCallModel(QualifierEntity(IdentifierEntity("this"), IdentifierEntity("asDynamic")), emptyList()),
                    StatementCallModel(IdentifierEntity("set"), listOf(
                            IdentifierEntity(nodeContext.name),
                            IdentifierEntity("value")
                    )))
            )

            is FunctionFromCallSignature -> {
                val chainCallModel = ChainCallModel(
                        StatementCallModel(QualifierEntity(IdentifierEntity("this"), IdentifierEntity("asDynamic")), emptyList()),
                        StatementCallModel(IdentifierEntity("invoke"), nodeContext.params))
                listOf(if (type.isUnit()) {
                    chainCallModel
                } else {
                    ReturnStatementModel(chainCallModel)
                })
            }
            is FunctionFromMethodSignatureDeclaration -> {
                val bodyStatement = ChainCallModel(
                        StatementCallModel(
                                QualifierEntity(
                                        IdentifierEntity("this"),
                                        IdentifierEntity("asDynamic")
                                )
                                , emptyList()),
                        StatementCallModel(IdentifierEntity(nodeContext.name), nodeContext.params)
                )

                listOf(if (type.isUnit()) {
                    bodyStatement
                } else {
                    ReturnStatementModel(bodyStatement)
                })
            }
            else -> emptyList()
        }
    }

    private fun convertTypeParams(typeParameters: List<TypeValueNode>): List<TypeParameterModel> {
        return typeParameters.map { typeParam ->
            TypeParameterModel(
                    type = TypeValueModel(typeParam.value, listOf(), null, typeParam.getFqName()),
                    constraints = typeParam.params
                            .map { param -> param.process(TranslationContext.TYPE_CONSTRAINT) }
                            .filterNot { (it is TypeValueModel) && (it.value == IMPOSSIBLE_CONSTRAINT) }
            )
        }
    }

    private fun ClassNode.convertToClassModel(): TopLevelModel {
        val membersSplitted = split(members)

        val generatedMethods = mutableListOf<MemberModel>()
        val parentModelEntities = convertParentEntities(parentEntities) {
            generatedMethods.addAll(it)
        }
        return ClassModel(
                name = name,
                members = membersSplitted.dynamic + generatedMethods,
                companionObject = if (membersSplitted.static.isNotEmpty()) {
                    ObjectModel(
                            IdentifierEntity(""),
                            membersSplitted.static,
                            emptyList(),
                            VisibilityModifierModel.DEFAULT,
                            null
                    )
                } else {
                    null
                },
                primaryConstructor = primaryConstructor?.let { constructor ->
                    ConstructorModel(
                            parameters = constructor.parameters.map { param -> param.process() },
                            typeParameters = convertTypeParams(constructor.typeParameters),
                            generated = constructor.generated
                    )
                },
                typeParameters = convertTypeParams(typeParameters),
                parentEntities = parentModelEntities,
                annotations = exportQualifier.toAnnotation(),
                comment = null,
                external = true,
                abstract = false,
                visibilityModifier = VisibilityModifierModel.DEFAULT
        )
    }

    private fun InterfaceNode.convertToInterfaceModel(): InterfaceModel {
        val membersSplitted = split(members)

        return InterfaceModel(
                name = name,
                members = membersSplitted.dynamic,
                companionObject = if (membersSplitted.static.isNotEmpty()) {
                    ObjectModel(
                            IdentifierEntity(""),
                            membersSplitted.static,
                            emptyList(),
                            VisibilityModifierModel.DEFAULT,
                            null
                    )
                } else {
                    null
                },
                typeParameters = convertTypeParams(typeParameters),
                parentEntities = convertParentEntities(parentEntities),
                annotations = exportQualifier.toAnnotation(),
                comment = null,
                external = true,
                visibilityModifier = VisibilityModifierModel.DEFAULT
        )
    }

    private fun MethodNode.process(override: NameEntity? = null): MethodModel {
        // TODO: how ClassModel end up here?
        return MethodModel(
                name = IdentifierEntity(name),
                parameters = parameters.map { param -> param.process() },
                type = type.process(),
                typeParameters = convertTypeParams(typeParameters),

                static = static,

                override = override,
                operator = operator,
                annotations = resolveAnnotations(),

                open = open
        )
    }

    fun TopLevelEntity.convertToModel(): TopLevelModel? {
        return when (this) {
            is ClassNode -> convertToClassModel()
            is InterfaceNode -> convertToInterfaceModel()
            is EnumNode -> {
                EnumModel(
                        name = name,
                        values = values.map { token -> EnumTokenModel(token.value, token.meta) },
                        visibilityModifier = VisibilityModifierModel.DEFAULT,
                        comment = null
                )
            }
            is FunctionNode -> {
                val context = if (inline) {
                    TranslationContext.INLINE_EXTENSION
                } else {
                    TranslationContext.IRRELEVANT
                }
                FunctionModel(
                        name = name,
                        parameters = parameters.map { param -> param.process(context) },
                        type = type.process(),

                        typeParameters = convertTypeParams(typeParameters),
                        annotations = exportQualifier.toAnnotation(),
                        export = export,
                        inline = inline,
                        operator = operator,
                        extend = extend.convert(),
                        body = resolveBody(),
                        visibilityModifier = VisibilityModifierModel.DEFAULT,
                        comment = comment
                )
            }
            is VariableNode -> VariableModel(
                    name = name,
                    type = type.process(),
                    annotations = exportQualifier.toAnnotation(),
                    immutable = immutable,
                    inline = inline,
                    initializer = null,
                    get = resolveGetter(),
                    set = resolveSetter(),
                    typeParameters = convertTypeParams(typeParameters),
                    extend = extend.convert(),
                    visibilityModifier = VisibilityModifierModel.DEFAULT,
                    comment = null
            )
            is ObjectNode -> ObjectModel(
                    name = name,
                    members = members.mapNotNull { member -> member.process() },
                    parentEntities = convertParentEntities(parentEntities),
                    visibilityModifier = VisibilityModifierModel.DEFAULT,
                    comment = null
            )
            is TypeAliasNode -> {
                TypeAliasModel(
                        name = name,
                        typeReference = typeReference.process(),
                        typeParameters = typeParameters.map { typeParameter -> TypeParameterModel(TypeValueModel(typeParameter, listOf(), null, null), emptyList()) },
                        visibilityModifier = VisibilityModifierModel.DEFAULT,
                        comment = null
                )
            }
            else -> {
                logger.debug("skipping ${this}")
                null
            }
        }
    }

}


private class NodeConverter(private val node: SourceSetNode, private val uidToNameMapper: UidMapper) {

    fun convert(): SourceSetModel {
        return SourceSetModel(
                sourceName = node.sourceName,
                sources = node.sources.flatMap { source ->
                    val rootFile = File(source.fileName)
                    val fileName = rootFile.normalize().absolutePath

                    val generated = mutableListOf<SourceFileModel>()
                    val root = DocumentConverter(source.root, uidToNameMapper).convert(source.fileName, generated)

                    val module = SourceFileModel(
                            name = source.name,
                            fileName = fileName,
                            root = root,
                            referencedFiles = source.referencedFiles.map { referenceFile ->
                                val absolutePath = rootFile.resolveSibling(referenceFile).normalize().absolutePath
                                absolutePath
                            })

                    generated + listOf(module)
                }
        )
    }
}

private class ReferenceVisitor(private val visit: (String, FqNode) -> Unit) : NodeTypeLowering {
    override fun lowerClassLikeNode(declaration: ClassLikeNode, owner: DocumentRootNode): ClassLikeNode {
        visit(declaration.uid, FqNode(declaration, owner.qualifiedPackageName.appendLeft(declaration.name)))
        return super.lowerClassLikeNode(declaration, owner)
    }

    override fun lowerTypeAliasNode(declaration: TypeAliasNode, owner: DocumentRootNode): TypeAliasNode {
        visit(declaration.uid, FqNode(declaration, owner.qualifiedPackageName.appendLeft(declaration.name)))
        return super.lowerTypeAliasNode(declaration, owner)
    }

    override fun lowerEnumNode(declaration: EnumNode, owner: DocumentRootNode): EnumNode {
        visit(declaration.uid, FqNode(declaration, owner.qualifiedPackageName.appendLeft(declaration.name)))
        return super.lowerEnumNode(declaration, owner)
    }

    fun process(sourceSet: SourceSetNode) {
        sourceSet.sources.forEach { source -> lowerDocumentRoot(source.root) }
    }
}

fun SourceSetNode.introduceModels(uidToFqNameMapper: UidMutableMapper): SourceSetModel {
    ReferenceVisitor { uid, fqModel ->
        uidToFqNameMapper[uid] = fqModel
    }.process(this)

    return NodeConverter(this, uidToFqNameMapper).convert()
}