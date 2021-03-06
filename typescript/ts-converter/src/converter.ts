import {DukatLanguageServiceHost} from "./DukatLanguageServiceHost";
import {AstConverter} from "./AstConverter";
import * as ts from "typescript";
import {createLogger} from "./Logger";
import {FileResolver} from "./FileResolver";
import {AstFactory} from "./ast/AstFactory";
import {SourceFileDeclaration} from "./ast/ast";
import * as declarations from "declarations";
import {DeclarationResolver} from "./DeclarationResolver";
import {LibraryDeclarationsVisitor} from "./ast/LibraryDeclarationsVisitor";
import {ExportContext} from "./ExportContext";
import {AstVisitor} from "./AstVisitor";
import {DocumentCache} from "./DocumentCache";
import * as fs from "fs";

function createFileResolver(): FileResolver {
  return new FileResolver();
}

let cache = new DocumentCache();

function buildLibSet(stdLib: string): Set<string> {
  let host = new DukatLanguageServiceHost(new FileResolver(), stdLib);
  host.register(stdLib);
  let languageService = ts.createLanguageService(host, (ts as any).createDocumentRegistryInternal(void 0, void 0, cache || void 0));
  const program = languageService.getProgram();

  return getLibPaths(program, program.getSourceFile(stdLib), ts.getDirectoryPath(stdLib));
}

function getLibPaths(program: ts.Program, libPath: ts.SourceFile | undefined, defaultLibraryPath: string, libs: Set<string> = new Set()) {
  if (libPath === undefined) {
    return libs;
  }

  if (libs.has(libPath.fileName)) {
    return libs;
  }

  libs.add(libPath.fileName);
  libPath.libReferenceDirectives.forEach(libReference => {
    getLibPaths(program, program.getLibFileFromReference(libReference), defaultLibraryPath, libs);
  });

  return libs;
}


class SourceBundleBuilder {
  private astFactory = new AstFactory();
  private program = this.createProgram();

  private libVisitor = new LibraryDeclarationsVisitor(this.program.getTypeChecker(), getLibPaths(this.program, this.program.getSourceFile(this.stdLib), ts.getDirectoryPath(this.stdLib)));
  private astConverter: AstConverter = this.createAstConverter(this.libVisitor);

  constructor(
    private stdLib: string,
    private files: Array<string>
  ) {
  }

  private createAstConverter(libVisitor: LibraryDeclarationsVisitor): AstConverter {
    let astConverter = new AstConverter(
      new ExportContext((node: ts.Node) => libVisitor.isLibDeclaration(node)),
      this.program.getTypeChecker(),
      new DeclarationResolver(this.program),
      this.astFactory,
      new class implements AstVisitor {
        visitType(type: ts.TypeNode): void {
          libVisitor.process(type);
        }
      }
    );

    libVisitor.createDeclarations = (node: ts.Node) => astConverter.convertTopLevelStatement(node);
    return astConverter;
  }

  private createSourceSet(fileName: string): Array<SourceFileDeclaration> {
    let logger = createLogger("converter");

    return this.createFileDeclarations(fileName, this.program);
  }

  createFileDeclarations(fileName: string, program: ts.Program, result: Map<string, SourceFileDeclaration> = new Map()): Array<SourceFileDeclaration> {
    if (result.has(fileName)) {
      return [];
    }
    let fileDeclaration = this.astConverter.createSourceFileDeclaration(program.getSourceFile(fileName));
    result.set(fileName, fileDeclaration);

    let root = fileDeclaration.getRoot();
    if (root) {
      root.getImportsList().map(it => it.getReferencedfile())
        .concat(root.getReferencesList().map(it => it.getReferencedfile()))
        .filter(it => fs.existsSync(it))
        .forEach(resourceFileName => {
          this.createFileDeclarations(resourceFileName, program, result);
        });
    }

    return Array.from(result.values());
  }

  private createProgram(): ts.Program {
    let host = new DukatLanguageServiceHost(createFileResolver(), this.stdLib);
    this.files.forEach(fileName => host.register(fileName));
    let languageService = ts.createLanguageService(host, (ts as any).createDocumentRegistryInternal(void 0, void 0, cache || void 0));
    const program = languageService.getProgram();

    if (program == null) {
      throw new Error("failed to create languageService");
    }

    return program;
  }

  createBundle(): declarations.SourceBundleDeclarationProto {
    let sourceSets = this.files.map(fileName => {
      return this.astFactory.createSourceSet([fileName], this.createSourceSet(fileName));
    });

    let sourceSetBundle = new declarations.SourceBundleDeclarationProto();

    let libRootUid = "<LIBROOT>";

    let libFiles: Array<SourceFileDeclaration> = [];
    this.libVisitor.forEachDeclaration((declarations, resourceName) => {
      libFiles.push(this.astFactory.createSourceFileDeclaration(
        resourceName, this.astFactory.createModuleDeclaration(
          this.astFactory.createIdentifierDeclarationAsNameEntity(libRootUid),
          [],
          [],
          declarations,
          [],
          libRootUid,
          libRootUid,
          true
        )
      ));
    });

    sourceSets.push(this.astFactory.createSourceSet([libRootUid], libFiles));

    sourceSetBundle.setSourcesList(sourceSets);
    return sourceSetBundle;
  }
}

export function createBundle(stdlib: string, files: Array<string>) {
  return new SourceBundleBuilder(stdlib, files).createBundle();
}