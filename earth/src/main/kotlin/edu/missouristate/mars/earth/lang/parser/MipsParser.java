/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiBuilder.Marker;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;
import static com.intellij.lang.parser.GeneratedParserUtilBase.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiParser;
import com.intellij.lang.LightPsiParser;

@SuppressWarnings({"SimplifiableIfStatement", "UnusedAssignment"})
public class MipsParser implements PsiParser, LightPsiParser {

  public ASTNode parse(IElementType t, PsiBuilder b) {
    parseLight(t, b);
    return b.getTreeBuilt();
  }

  public void parseLight(IElementType t, PsiBuilder b) {
    boolean r;
    b = adapt_builder_(t, b, this, null);
    Marker m = enter_section_(b, 0, _COLLAPSE_, null);
    r = parse_root_(t, b);
    exit_section_(b, 0, m, t, r, true, TRUE_CONDITION);
  }

  protected boolean parse_root_(IElementType t, PsiBuilder b) {
    return parse_root_(t, b, 0);
  }

  static boolean parse_root_(IElementType t, PsiBuilder b, int l) {
    return mipsFile(b, l + 1);
  }

  /* ********************************************************** */
  // ' ' | '\t' | COMMA
  static boolean delimiter(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "delimiter")) return false;
    boolean r;
    r = consumeToken(b, " ");
    if (!r) r = consumeToken(b, "\\t");
    if (!r) r = consumeToken(b, COMMA);
    return r;
  }

  /* ********************************************************** */
  // number_range
  //                   | number_list
  //                   | string_literal
  //                   | IDENTIFIER
  public static boolean directive_arg(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "directive_arg")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, DIRECTIVE_ARG, "<directive arg>");
    r = number_range(b, l + 1);
    if (!r) r = number_list(b, l + 1);
    if (!r) r = string_literal(b, l + 1);
    if (!r) r = consumeToken(b, IDENTIFIER);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // DIRECTIVE directive_arg*
  public static boolean directive_statement(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "directive_statement")) return false;
    if (!nextTokenIs(b, DIRECTIVE)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, DIRECTIVE_STATEMENT, null);
    r = consumeToken(b, DIRECTIVE);
    p = r; // pin = 1
    r = r && directive_statement_1(b, l + 1);
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // directive_arg*
  private static boolean directive_statement_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "directive_statement_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!directive_arg(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "directive_statement_1", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // OPERATOR instruction_args*
  public static boolean instruction(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "instruction")) return false;
    if (!nextTokenIs(b, OPERATOR)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, INSTRUCTION, null);
    r = consumeToken(b, OPERATOR);
    p = r; // pin = 1
    r = r && instruction_1(b, l + 1);
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // instruction_args*
  private static boolean instruction_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "instruction_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!instruction_args(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "instruction_1", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // register_literal
  //                   | number_literal
  //                   | IDENTIFIER
  public static boolean instruction_arg(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "instruction_arg")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, INSTRUCTION_ARG, "<instruction arg>");
    r = register_literal(b, l + 1);
    if (!r) r = number_literal(b, l + 1);
    if (!r) r = consumeToken(b, IDENTIFIER);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // instruction_arg (delimiter+ instruction_arg)*
  static boolean instruction_args(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "instruction_args")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = instruction_arg(b, l + 1);
    p = r; // pin = 1
    r = r && instruction_args_1(b, l + 1);
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // (delimiter+ instruction_arg)*
  private static boolean instruction_args_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "instruction_args_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!instruction_args_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "instruction_args_1", c)) break;
    }
    return true;
  }

  // delimiter+ instruction_arg
  private static boolean instruction_args_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "instruction_args_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = instruction_args_1_0_0(b, l + 1);
    r = r && instruction_arg(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // delimiter+
  private static boolean instruction_args_1_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "instruction_args_1_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = delimiter(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!delimiter(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "instruction_args_1_0_0", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // INTEGER_5
  //                            | INTEGER_16
  //                            | INTEGER_16U
  //                            | INTEGER_32
  static boolean integer_literal(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "integer_literal")) return false;
    boolean r;
    r = consumeToken(b, INTEGER_5);
    if (!r) r = consumeToken(b, INTEGER_16);
    if (!r) r = consumeToken(b, INTEGER_16U);
    if (!r) r = consumeToken(b, INTEGER_32);
    return r;
  }

  /* ********************************************************** */
  // label_identifier COLON
  public static boolean label_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "label_definition")) return false;
    if (!nextTokenIs(b, "<label definition>", IDENTIFIER, OPERATOR)) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, LABEL_DEFINITION, "<label definition>");
    r = label_identifier(b, l + 1);
    r = r && consumeToken(b, COLON);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER | OPERATOR
  public static boolean label_identifier(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "label_identifier")) return false;
    if (!nextTokenIs(b, "<label identifier>", IDENTIFIER, OPERATOR)) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, LABEL_IDENTIFIER, "<label identifier>");
    r = consumeToken(b, IDENTIFIER);
    if (!r) r = consumeToken(b, OPERATOR);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // statementList
  static boolean mipsFile(PsiBuilder b, int l) {
    return statementList(b, l + 1);
  }

  /* ********************************************************** */
  // number_literal (delimiter+ number_literal)*
  static boolean number_list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "number_list")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = number_literal(b, l + 1);
    p = r; // pin = 1
    r = r && number_list_1(b, l + 1);
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // (delimiter+ number_literal)*
  private static boolean number_list_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "number_list_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!number_list_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "number_list_1", c)) break;
    }
    return true;
  }

  // delimiter+ number_literal
  private static boolean number_list_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "number_list_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = number_list_1_0_0(b, l + 1);
    r = r && number_literal(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // delimiter+
  private static boolean number_list_1_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "number_list_1_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = delimiter(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!delimiter(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "number_list_1_0_0", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // integer_literal | REAL_NUMBER
  public static boolean number_literal(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "number_literal")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, NUMBER_LITERAL, "<number literal>");
    r = integer_literal(b, l + 1);
    if (!r) r = consumeToken(b, REAL_NUMBER);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // number_literal COLON number_literal
  public static boolean number_range(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "number_range")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, NUMBER_RANGE, "<number range>");
    r = number_literal(b, l + 1);
    r = r && consumeToken(b, COLON);
    r = r && number_literal(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // REGISTER_NUMBER
  //                              | REGISTER_NAME
  //                              | FP_REGISTER_NAME
  static boolean register_identifier(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_identifier")) return false;
    boolean r;
    r = consumeToken(b, REGISTER_NUMBER);
    if (!r) r = consumeToken(b, REGISTER_NAME);
    if (!r) r = consumeToken(b, FP_REGISTER_NAME);
    return r;
  }

  /* ********************************************************** */
  // register_offset? delimiter* LPAREN delimiter* register_identifier delimiter* RPAREN
  //                   | register_identifier
  public static boolean register_literal(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_literal")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, REGISTER_LITERAL, "<register literal>");
    r = register_literal_0(b, l + 1);
    if (!r) r = register_identifier(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // register_offset? delimiter* LPAREN delimiter* register_identifier delimiter* RPAREN
  private static boolean register_literal_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_literal_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = register_literal_0_0(b, l + 1);
    r = r && register_literal_0_1(b, l + 1);
    r = r && consumeToken(b, LPAREN);
    r = r && register_literal_0_3(b, l + 1);
    r = r && register_identifier(b, l + 1);
    r = r && register_literal_0_5(b, l + 1);
    r = r && consumeToken(b, RPAREN);
    exit_section_(b, m, null, r);
    return r;
  }

  // register_offset?
  private static boolean register_literal_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_literal_0_0")) return false;
    register_offset(b, l + 1);
    return true;
  }

  // delimiter*
  private static boolean register_literal_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_literal_0_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!delimiter(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "register_literal_0_1", c)) break;
    }
    return true;
  }

  // delimiter*
  private static boolean register_literal_0_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_literal_0_3")) return false;
    while (true) {
      int c = current_position_(b);
      if (!delimiter(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "register_literal_0_3", c)) break;
    }
    return true;
  }

  // delimiter*
  private static boolean register_literal_0_5(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_literal_0_5")) return false;
    while (true) {
      int c = current_position_(b);
      if (!delimiter(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "register_literal_0_5", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // IDENTIFIER (delimiter* (PLUS | MINUS) delimiter* number_literal)?
  //                   | number_literal
  public static boolean register_offset(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_offset")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, REGISTER_OFFSET, "<register offset>");
    r = register_offset_0(b, l + 1);
    if (!r) r = number_literal(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // IDENTIFIER (delimiter* (PLUS | MINUS) delimiter* number_literal)?
  private static boolean register_offset_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_offset_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IDENTIFIER);
    r = r && register_offset_0_1(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (delimiter* (PLUS | MINUS) delimiter* number_literal)?
  private static boolean register_offset_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_offset_0_1")) return false;
    register_offset_0_1_0(b, l + 1);
    return true;
  }

  // delimiter* (PLUS | MINUS) delimiter* number_literal
  private static boolean register_offset_0_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_offset_0_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = register_offset_0_1_0_0(b, l + 1);
    r = r && register_offset_0_1_0_1(b, l + 1);
    r = r && register_offset_0_1_0_2(b, l + 1);
    r = r && number_literal(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // delimiter*
  private static boolean register_offset_0_1_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_offset_0_1_0_0")) return false;
    while (true) {
      int c = current_position_(b);
      if (!delimiter(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "register_offset_0_1_0_0", c)) break;
    }
    return true;
  }

  // PLUS | MINUS
  private static boolean register_offset_0_1_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_offset_0_1_0_1")) return false;
    boolean r;
    r = consumeToken(b, PLUS);
    if (!r) r = consumeToken(b, MINUS);
    return r;
  }

  // delimiter*
  private static boolean register_offset_0_1_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "register_offset_0_1_0_2")) return false;
    while (true) {
      int c = current_position_(b);
      if (!delimiter(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "register_offset_0_1_0_2", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // label_definition
  //                     | directive_statement
  //                     | instruction
  //                     | COMMA
  //                     | EOL
  static boolean statement(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "statement")) return false;
    boolean r;
    r = label_definition(b, l + 1);
    if (!r) r = directive_statement(b, l + 1);
    if (!r) r = instruction(b, l + 1);
    if (!r) r = consumeToken(b, COMMA);
    if (!r) r = consumeToken(b, EOL);
    return r;
  }

  /* ********************************************************** */
  // statement*
  static boolean statementList(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "statementList")) return false;
    while (true) {
      int c = current_position_(b);
      if (!statement(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "statementList", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // LQUOTE QUOTED_STRING RQUOTE
  public static boolean string_literal(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "string_literal")) return false;
    if (!nextTokenIs(b, LQUOTE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 3, LQUOTE, QUOTED_STRING, RQUOTE);
    exit_section_(b, m, STRING_LITERAL, r);
    return r;
  }

}
