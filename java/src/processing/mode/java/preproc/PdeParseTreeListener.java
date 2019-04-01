/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2019 The Processing Foundation

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License version 2
  as published by the Free Software Foundation.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.mode.java.preproc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.*;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;

import processing.app.Preferences;
import processing.app.SketchException;
import processing.core.PApplet;
import processing.mode.java.pdex.TextTransform;
import processing.mode.java.preproc.PdePreprocessor.Mode;


/**
 * ANTLR tree traversal listener that preforms code rewrites as part of sketch preprocessing.
 *
 * <p>
 *   ANTLR tree traversal listener that preforms code rewrites as part of sketch preprocessing,
 *   turning sketch source into compilable Java code. Note that this emits both the Java source
 *   when using javac directly as part of {JavaBuild} as well as {TextTransform.Edit}s when using
 *   the JDT via the {PreprocessingService}.
 * </p>
 */
public class PdeParseTreeListener extends ProcessingBaseListener {

  protected final static String version = "3.0.0";
  
  protected String sketchName;
  protected boolean isTested;
  protected TokenStreamRewriter rewriter;
  
  protected Mode mode = Mode.JAVA;
  protected boolean foundMain;
  
  protected int lineOffset;
  
  protected ArrayList<String> coreImports = new ArrayList<String>();
  protected ArrayList<String> defaultImports = new ArrayList<String>();
  protected ArrayList<String> codeFolderImports = new ArrayList<String>();
  protected ArrayList<String> foundImports = new ArrayList<String>();
  protected ArrayList<TextTransform.Edit> edits = new ArrayList<>();

  private String indent1 = "";
  private String indent2 = "";
  private String indent3 = "";
  
  protected String sketchWidth;
  protected String sketchHeight;
  protected String sketchRenderer;

  protected boolean hasSettingsMethod;

  protected boolean isSizeValidInGlobal;

  protected SketchException sketchException;

  /**
   * Create a new listener.
   *
   * @param tokens The tokens over which to rewrite.
   * @param sketchName The name of the sketch being traversed.
   */
  PdeParseTreeListener(BufferedTokenStream tokens, String sketchName) {
    rewriter = new TokenStreamRewriter(tokens);
    this.sketchName = sketchName;
  }
  
  protected void setCodeFolderImports(List<String> codeFolderImports) {
    this.codeFolderImports.clear();
    this.codeFolderImports.addAll(codeFolderImports);
  }
  
  protected void setCoreImports(String[] coreImports) {
    setCoreImports(Arrays.asList(coreImports));
  }
  
  protected void setCoreImports(List<String> coreImports) {
    this.coreImports.clear();
    this.coreImports.addAll(coreImports);
  }
  
  protected void setDefaultImports(String[] defaultImports) {
    setDefaultImports(Arrays.asList(defaultImports));
  }
  
  protected void setDefaultImports(List<String> defaultImports) {
    this.defaultImports.clear();
    this.defaultImports.addAll(defaultImports);
  }
  
  protected void setIndent(int indent) {
    final char[] indentChars = new char[indent];
    Arrays.fill(indentChars, ' ');
    indent1 = new String(indentChars);
    indent2 = indent1 + indent1;
    indent3 = indent2 + indent1;
  }
  
  public void setTested(boolean isTested) {
    this.isTested = isTested;
  }
  
  public boolean foundMain() {
    return foundMain;
  }
  
  public String getOutputProgram() {
    return rewriter.getText();
  }
  
  public PreprocessorResult getResult() throws SketchException {
    return new PreprocessorResult(mode, lineOffset, sketchName, foundImports, edits);
  }

  protected boolean reportSketchException(SketchException sketchException) {
    if (this.sketchException == null) {
      this.sketchException = sketchException;
      return true;
    }
    return false;
  }

  public SketchException getSketchException() {
    return sketchException;
  }
  
  // ------------------------ writers
  
  protected void writeHeader(PrintWriter header) {       
    if (!isTested) writePreprocessorComment(header);
    writeImports(header);
    if (mode == Mode.STATIC || mode == Mode.ACTIVE) writeClassHeader(header);
    if (mode == Mode.STATIC) writeStaticSketchHeader(header);
  }
  
  protected void writePreprocessorComment(PrintWriter header) {
    incLineOffset(); header.println(String.format(
      "/* autogenerated by Processing preprocessor v%s on %s */",
      version, new SimpleDateFormat("YYYY-MM-dd").format(new Date())));
  }
  
  protected void writeImports(PrintWriter header) {
    writeImportList(header, coreImports);
    writeImportList(header, codeFolderImports);
    writeImportList(header, foundImports);
    writeImportList(header, defaultImports);
  }
  
  protected void writeImportList(PrintWriter header, List<String> imports) {
    writeImportList(header, imports.toArray(new String[0]));
  }
  
  protected void writeImportList(PrintWriter header, String[] imports) {
    for (String importDecl : imports) {
      incLineOffset(); header.println("import " + importDecl + ";");
    }
    if (imports.length > 0) {
      incLineOffset(); header.println();
    }
  }
  
  protected void writeClassHeader(PrintWriter header) {
    incLineOffset(); header.println("public class " + sketchName + " extends PApplet {");
    incLineOffset(); header.println();
  }
  
  protected void writeStaticSketchHeader(PrintWriter header) {
    incLineOffset(); header.println(indent1 + "public void setup() {");
  }
  
  protected void writeFooter(PrintWriter footer) {
    if (mode == Mode.STATIC) writeStaticSketchFooter(footer);
    if (mode == Mode.STATIC || mode == Mode.ACTIVE) {
      writeExtraFieldsAndMethods(footer);
      if (!foundMain) writeMain(footer); 
      writeClassFooter(footer);
    }
  }
  
  protected void writeStaticSketchFooter(PrintWriter footer) {
    footer.println(indent2 +   "noLoop();");
    footer.println(indent1 + "}");
  }

  protected void writeExtraFieldsAndMethods(PrintWriter classBody) {
    // can be overriden

    if (!isSizeValidInGlobal) {
      return;
    }

    if (sketchWidth == null || sketchHeight == null || hasSettingsMethod) {
      return;
    }

    StringJoiner argJoiner = new StringJoiner(",");
    argJoiner.add(sketchWidth);
    argJoiner.add(sketchHeight);
    if (sketchRenderer != null) {
      argJoiner.add(sketchRenderer);
    }

    String settingsBody = String.format("size(%s);", argJoiner.toString());

    classBody.println();
    classBody.println(indent1 + String.format("public void settings() { %s }", settingsBody));
}
  
  protected void writeMain(PrintWriter footer) {
    footer.println();
    footer.println(indent1 + "static public void main(String[] passedArgs) {");
    footer.print  (indent2 +   "String[] appletArgs = new String[] { ");

    { // assemble line with applet args
      if (Preferences.getBoolean("export.application.fullscreen")) {
        footer.print("\"" + PApplet.ARGS_FULL_SCREEN + "\", ");

        String bgColor = Preferences.get("run.present.bgcolor");
        footer.print("\"" + PApplet.ARGS_BGCOLOR + "=" + bgColor + "\", ");

        if (Preferences.getBoolean("export.application.stop")) {
          String stopColor = Preferences.get("run.present.stop.color");
          footer.print("\"" + PApplet.ARGS_STOP_COLOR + "=" + stopColor + "\", ");
        } else {
          footer.print("\"" + PApplet.ARGS_HIDE_STOP + "\", ");
        }
      }
      footer.print("\"" + sketchName + "\"");
    }
    
    footer.println(" };");
    
    footer.println(indent2 +   "if (passedArgs != null) {");
    footer.println(indent3 +     "PApplet.main(concat(appletArgs, passedArgs));");
    footer.println(indent2 +   "} else {");
    footer.println(indent3 +     "PApplet.main(appletArgs);");
    footer.println(indent2 +   "}");
    footer.println(indent1 + "}");
  }
  
  protected void writeClassFooter(PrintWriter footer) {
    footer.println("}");
  }

  // --------------------------------------------------- listener impl
  
  /**
   * Wrap the sketch code inside a class definition and
   * add all imports found to the top incl. the default ones
   */
  public void exitProcessingSketch(ProcessingParser.ProcessingSketchContext ctx) {
    { // header
      StringWriter headerSW = new StringWriter();
      PrintWriter headerPW = new PrintWriter(headerSW);
      writeHeader(headerPW);
      createInsertBefore(ctx, 0, headerSW.getBuffer().toString());
    }

    { // footer
      StringWriter footerSW = new StringWriter();
      PrintWriter footerPW = new PrintWriter(footerSW);
      footerPW.println();
      writeFooter(footerPW);

      TokenStream tokenStream = rewriter.getTokenStream();
      int tokens = tokenStream.size();
      int length = tokenStream.get(tokens-1).getStopIndex() + 1;

      String footerText = footerSW.getBuffer().toString();

      edits.add(TextTransform.Edit.insert(length, footerText));
      rewriter.insertAfter(tokens, footerText);
    }
  }

  public void exitSpecialMethodDeclaration(ProcessingParser.SpecialMethodDeclarationContext ctx) {
    if (!ctx.getChild(0).getText().equals("public")) {
      createInsertBefore(ctx, ctx.start, "public ");
    }
  }

  protected void incLineOffset() {
    lineOffset++;
  }

  public void exitApiSizeFunction(ProcessingParser.ApiSizeFunctionContext ctx) {
    // this tree climbing could be avoided if grammar is
    // adjusted to force context of size()

    ParserRuleContext testCtx =
      ctx.getParent() // apiFunction
      .getParent() // methodInvocation
      .getParent() // statementExpression
      .getParent() // expressionStatement
      .getParent() // statementWithoutTrailingSubstatement
      .getParent() // statement
      .getParent()
      .getParent(); // block or staticProcessingSketch

    boolean isInGlobal =
      testCtx instanceof ProcessingParser.StaticProcessingSketchContext;

    isSizeValidInGlobal = false;

    if (isInGlobal) {
      isSizeValidInGlobal = true;
      sketchWidth = ctx.getChild(2).getText();
      if (PApplet.parseInt(sketchWidth, -1) == -1 &&
          !sketchWidth.equals("displayWidth")) {
        isSizeValidInGlobal = false;
      }
      sketchHeight = ctx.getChild(4).getText();
      if (PApplet.parseInt(sketchHeight, -1) == -1 &&
          !sketchHeight.equals("displayHeight")) {
        isSizeValidInGlobal = false;
      }
      if (ctx.getChildCount() > 6) {
        sketchRenderer = ctx.getChild(6).getText();
        if (!(sketchRenderer.equals("P2D") ||
              sketchRenderer.equals("P3D") ||
              sketchRenderer.equals("OPENGL") ||
              sketchRenderer.equals("JAVA2D") ||
              sketchRenderer.equals("FX2D"))) {
          isSizeValidInGlobal = false;
        }
      }
      if (isSizeValidInGlobal) {
        // TODO: uncomment if size is supposed to be removed from setup()
        createInsertBefore(ctx, ctx.start, "/* commented out by preprocessor: ");
        createInsertAfter(ctx, ctx.stop, " */");
      }
    }
  }

  /**
   * Remove import declarations, they will be included in the header.
   */
  public void exitImportDeclaration(ProcessingParser.ImportDeclarationContext ctx) {
    createDelete(ctx, ctx.start, ctx.stop);
  }
  
  /**
   * Save qualified import name (with static modifier when present)
   * for inclusion in the header.
   */
  public void exitImportString(ProcessingParser.ImportStringContext ctx) {
    Interval interval =
      new Interval(ctx.start.getStartIndex(), ctx.stop.getStopIndex());
    String importString = ctx.start.getInputStream().getText(interval);
    foundImports.add(importString);
  }

  /**
   * Any floating point number that has not float / double suffix
   * will get a 'f' appended to make it float.
   */
  public void exitDecimalfloatingPointLiteral(ProcessingParser.DecimalfloatingPointLiteralContext ctx) {
    String cTxt = ctx.getText().toLowerCase();
    if (!cTxt.endsWith("f") && !cTxt.endsWith("d")) {
      createInsertAfter(ctx, ctx.stop, "f");
    }
  }

  /**
   * Detect "static sketches"
   */
  public void exitStaticProcessingSketch(ProcessingParser.StaticProcessingSketchContext ctx) {
    mode = Mode.STATIC;
  }
  
  /**
   * Detect "active sketches"
   */
  public void exitActiveProcessingSketch(ProcessingParser.ActiveProcessingSketchContext ctx) {
    mode = Mode.ACTIVE;
  }

  /**
   * Make any method "public" that has:
   * - no other access modifier
   * - return type "void"
   * - is either in the context of the sketch class
   * - or is in the context of a class definition that extends PApplet
   */
  public void exitMethodDeclaration(ProcessingParser.MethodDeclarationContext ctx) {
    ParserRuleContext memCtx = ctx.getParent();
    ParserRuleContext clsBdyDclCtx = memCtx.getParent();
    ParserRuleContext clsBdyCtx = clsBdyDclCtx.getParent();
    ParserRuleContext clsDclCtx = clsBdyCtx.getParent();

    boolean inSketchContext = 
      clsBdyCtx instanceof ProcessingParser.StaticProcessingSketchContext ||
      clsBdyCtx instanceof ProcessingParser.ActiveProcessingSketchContext;

    boolean inPAppletContext =
      inSketchContext || (
        clsDclCtx instanceof ProcessingParser.ClassDeclarationContext &&
        clsDclCtx.getChildCount() >= 4 && 
        clsDclCtx.getChild(2).getText().equals("extends") &&
        clsDclCtx.getChild(3).getText().endsWith("PApplet"));

    boolean voidType = ctx.getChild(0).getText().equals("void");

    // not the first, so no mod before
    boolean hasModifier = clsBdyDclCtx.getChild(0) != memCtx;

    if (!hasModifier && inPAppletContext && voidType) {
      createInsertBefore(ctx, memCtx.start, "public ");
    }

    if ((inSketchContext || inPAppletContext) && 
        hasModifier && 
        ctx.getChild(1).getText().equals("main")) {
      foundMain = true;
    }
  }

  /**
   * Change any "value converters" with the name of a primitive type
   * to their proper names:
   * int() --> parseInt()
   * float() --> parseFloat()
   * ...
   */
  public void exitFunctionWithPrimitiveTypeName(ProcessingParser.FunctionWithPrimitiveTypeNameContext ctx) {
    String fn = ctx.getChild(0).getText();
    if (!fn.equals("color")) {
      fn = "PApplet.parse" + fn.substring(0,1).toUpperCase() + fn.substring(1);
      createInsertBefore(ctx, ctx.start, fn);
      createDelete(ctx, ctx.start);
    }
  }

  /**
   * Fix "color type" to be "int".
   */
  public void exitColorPrimitiveType(ProcessingParser.ColorPrimitiveTypeContext ctx) {
    if (ctx.getText().equals("color")) {
      createInsertBefore(ctx, ctx.start, "int");
      createDelete(ctx, ctx.start, ctx.stop);
    }
  }

  /**
   * Fix hex color literal
   */
  public void exitHexColorLiteral(ProcessingParser.HexColorLiteralContext ctx) {
    createInsertBefore(
        ctx,
        ctx.start,
        ctx.getText().toUpperCase().replace("#","0xFF")
    );

    createDelete(ctx, ctx.start, ctx.stop);
  }

  private void createDelete(ParserRuleContext ctx, Token start) {
    rewriter.delete(start);
    edits.add(TextTransform.Edit.delete(start.getStartIndex(), start.getText().length()));
  }

  private void createDelete(ParserRuleContext ctx, Token start, Token stop) {
    rewriter.delete(start, stop);

    int startIndex = start.getStartIndex();
    int length = stop.getStopIndex() - startIndex + 1;

    edits.add(TextTransform.Edit.delete(
        startIndex,
        length
    ));
  }

  private void createInsertAfter(ParserRuleContext ctx, Token start, String text) {
    rewriter.insertAfter(start, text);

    edits.add(TextTransform.Edit.insert(
        start.getStopIndex() + 1,
        text
    ));
  }

  private void createInsertBefore(ParserRuleContext ctx, Token before, String text) {
    rewriter.insertBefore(before, text);

    edits.add(TextTransform.Edit.insert(
        before.getStartIndex(),
        text
    ));
  }

  private void createInsertBefore(ParserRuleContext ctx, int before, String text) {
    rewriter.insertBefore(before, text);

    edits.add(TextTransform.Edit.insert(
        before,
        text
    ));
  }

}