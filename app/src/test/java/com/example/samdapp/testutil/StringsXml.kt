package com.example.samdapp.testutil

import com.example.samdapp.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The default-locale English text of a string resource, read from app/src/main/res/values/
 * strings.xml (app/src is a declared input of the unit test task, so an edit to the copy reruns
 * the tests that read it). For tests whose claim is about the words themselves, such as "no
 * non-REAL card mentions a score"; a test about WHICH string is selected should compare ids.
 */
object StringsXml {

    private val values: Map<String, String> by lazy {
        val file = File(System.getProperty("user.dir")!!).let { cwd ->
            generateSequence(cwd) { it.parentFile }
                .map { File(it, "app/src/main/res/values/strings.xml") }
                .firstOrNull { it.isFile }
                ?: error("could not locate strings.xml from $cwd")
        }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private val namesById: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    fun text(id: Int): String {
        val name = namesById[id] ?: error("no R.string field has id $id")
        return values[name] ?: error("strings.xml has no <string name=\"$name\">")
    }
}
