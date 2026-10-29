/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.flutter.editor.dart

import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem

object DartSnippets {

  val SNIPPETS = listOf(
      SimpleCompletionItem(
          "stless",
          "Flutter StatelessWidget",
          4,
          """class NewWidget extends StatelessWidget {
  const NewWidget({super.key});

  @override
  Widget build(BuildContext context) {
    return const Placeholder();
  }
}"""
      ),
      SimpleCompletionItem(
          "stful",
          "Flutter StatefulWidget",
          4,
          """class NewWidget extends StatefulWidget {
  const NewWidget({super.key});

  @override
  State<NewWidget> createState() => _NewWidgetState();
}

class _NewWidgetState extends State<NewWidget> {
  @override
  Widget build(BuildContext context) {
    return const Placeholder();
  }
}"""
      ),
      SimpleCompletionItem(
          "build",
          "Widget build method",
          5,
          """@override
Widget build(BuildContext context) {
  return const Placeholder();
}"""
      ),
      SimpleCompletionItem(
          "initState",
          "State initState method",
          5,
          """@override
void initState() {
  super.initState();
}"""
      ),
      SimpleCompletionItem(
          "dispose",
          "State dispose method",
          5,
          """@override
void dispose() {
  super.dispose();
}"""
      ),
      SimpleCompletionItem(
          "setState",
          "State setState callback",
          5,
          """setState(() {
  
});"""
      ),
      SimpleCompletionItem(
          "main",
          "Flutter main() entrypoint",
          4,
          """void main() {
  runApp(const MyApp());
}"""
      )
  )

  val KEYWORDS = arrayOf(
      "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
      "const", "continue", "default", "do", "dynamic", "else", "enum", "export",
      "extends", "extension", "external", "factory", "false", "final", "finally",
      "for", "get", "if", "implements", "import", "in", "interface", "is", "late",
      "mixin", "new", "null", "on", "operator", "part", "required", "rethrow",
      "return", "set", "show", "static", "super", "switch", "sync", "this",
      "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield"
  )

  val FLUTTER_WIDGETS = arrayOf(
      "Widget", "StatelessWidget", "StatefulWidget", "State", "BuildContext", "Container",
      "Scaffold", "AppBar", "Text", "Column", "Row", "Center", "Expanded", "SizedBox",
      "Padding", "ListView", "ElevatedButton", "TextButton", "OutlinedButton", "IconButton",
      "FloatingActionButton", "Icon", "Icons", "Colors", "EdgeInsets", "MaterialApp",
      "ThemeData", "Navigator", "MaterialPageRoute", "FutureBuilder", "StreamBuilder",
      "SingleChildScrollView", "Card", "Divider", "Stack", "Positioned", "Align"
  )
}
