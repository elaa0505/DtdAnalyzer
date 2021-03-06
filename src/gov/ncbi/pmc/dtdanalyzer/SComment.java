/*
 * SComments.java
 */

package gov.ncbi.pmc.dtdanalyzer;

import java.util.*;
import java.io.*;
import java.util.regex.*;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.SAXParser;
import org.apache.commons.io.*;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Holds a single instance of a structured comment ("scomment") from the DTD.
 */
public class SComment {
    /**
     * Marks a comment that applies to a parameter entity definition.
     * For consistency, make sure this matches the definition in Entity.java.
     */
    public static final int PARAMETER_ENTITY = 1;

    /**
     * Marks a comment that applies to a general entity definition.
     * For consistency, make sure this matches the definition in Entity.java.
     */
    public static final int GENERAL_ENTITY = 2;

    /**
     * Marks a comment as belonging to an individual module.
     */
    public static final int MODULE = 3;

    /**
     * Marks a comment as belonging to an element
     */
    public static final int ELEMENT = 4;

    /**
     * Marks a comment as belonging to an attribute.
     */
    public static final int ATTRIBUTE = 5;

    /**
     * Structured comment processor.
     */
    private static String commentProcessor = "";


    /**
     * My type, one of the above integer constants.
     */
    private int type;

    /**
     * My name.  This is from the identifier after the special characters are
     * stripped away.  E.g. if the identifier is "<element>", the name is "element".
     */
    private String name;

    /**
     * Only for MODULE structured comments, the text immediately after the opening
     * comment tag is used for the title.  For other SComment types, this will be null;
     */
    private String title = null;

    /**
     * List of sections, indexed by section name.
     */
    private HashMap sections = new HashMap();

    /**
     * This is set to true if this scomment has a tag "root"
     */
    private boolean root = false;

    /**
     * Here is an XML parser that we use to check the validity of structured
     * comments as we see them.
     */
    private static SAXParser commentChecker = init();

    private static SAXParser init() {
        SAXParser sp = null;
        try {
            sp = SAXParserFactory.newInstance().newSAXParser();
        }
        catch (Exception e) {
            System.err.println("Fatal error during initialization:  " + e.getMessage());
            System.exit(1);
        }
        return sp;
    }



    /**
     * Creates a new instance of an SComment.  The argument is the identifer
     * such as "<split>" or "!dtd".  It is parsed first to determine the target type.
     */
    public SComment(String identifier) {
        if (identifier.startsWith("%")) {
            type = PARAMETER_ENTITY;
            // semicolon at the end is optional
            name = identifier.endsWith(";") ?
                  identifier.substring(1, identifier.length() - 1)
                : identifier.substring(1);
        }
        else if (identifier.startsWith("&")) {
            type = GENERAL_ENTITY;
            // semicolon at the end is optional
            name = identifier.endsWith(";") ?
                  identifier.substring(1, identifier.length() - 1)
                : identifier.substring(1);
        }
        else if (identifier.startsWith("<") && identifier.endsWith(">")) {
            type = ELEMENT;
            name = identifier.substring(1, identifier.length() - 1);
        }
        else if (identifier.startsWith("@")) {
            type = ATTRIBUTE;
            name = identifier.substring(1);
        }
        else {
            type = MODULE;
            // Name gets set later, from the relSysId of the current parsing location,
            // and not from the identifier.
            // Instead, use the identifier to set the title.
            if (identifier != null && !identifier.equals("")) {
                title = identifier;
            }
        }
    }

    public int getType() {
        return type;
    }
    public void setName(String n) {
        name = n;
    }

    public String getTitle() {
        return title;
    }

    public String getName() {
        return name;
    }

    /**
     * Add a new section to this structured comment.  Here is where (for now) we encapsulate
     * knowledge about the special handling of each section type, but see also GitHub
     * issue #13.
     * Special handling:
     *   tags - convert into a list of <tag> elements
     *   schematron - pass-thru
     *   json - pass-thru
     *   anything else - either pass-thru or process as Markdown
     */
    public void addSection(String name, String text) throws Exception
    {
        String sectionString = "";

        if (name.equals("tags")) {
            String[] tags = text.split("\\s+");
            String tagElems = "";
            for (int i = 0; i < tags.length; ++i) {
                String tag = tags[i];
                if (tag.equals("")) continue;
                sectionString += "<tag>" + tag + "</tag>";
                // Record any "root" tag:
                if (tag.equals("root")) root = true;
            }
        }
        else if (name.equals("schematron") || name.equals("json")) {
            sectionString = text;
        }
        else {
            sectionString = convertMarkdown(text);
        }

        // Need to check that this new section is a well-formed node set.  So, we'll
        // try to parse it as XML.
        try {
            InputSource xmlstr = new InputSource(new StringReader("<foo>" + sectionString + "</foo>"));
            DefaultHandler dh = new DefaultHandler();
            commentChecker.parse(xmlstr, dh);
        }
        catch (Exception e) {
            throw new Exception(
                "\nThe following annotation, after processing, is not valid XML:\n" +
                "-------------------------------------------------------------\n" +
                sectionString + "\n" +
                "-------------------------------------------------------------\n");
        }

        sections.put(name, sectionString);

    }

    // Here's the regular expression for a hyperlink to another element in the documentation
    // It will match `<banana>, but not \`<banana> (using zero-width negative lookbehind
    // assertion)
    private static Pattern elemLink = Pattern.compile("(?<!\\\\)\\`\\<(\\S+?)>");

    // The pattern for an attribute
    // FIXME:  Need to fix this regex so that it recognizes the full set of allowed chars.
    private static Pattern attrLink = Pattern.compile("(?<!\\\\)@([-_a-zA-Z]+)");

    // Patterns for entities are simpler, since they are closed by a ";"
    private static Pattern paramEntLink = Pattern.compile("(?<!\\\\)%(\\S+?);");
    private static Pattern genEntLink = Pattern.compile("(?<!\\\\)&(?!(#|fdft;|gt;|amp;|apos;|quot;))(\\S+?);");
    //private static Pattern genEntLink = Pattern.compile("(?<!\\\\)&(\\S+?);");

    // This pattern is used in HTML-style annotations, and removes the backslashes that
    // the user may have put in to disable auto-linking.  (In Markdown mode, pandoc
    // removes these for us.)
    private static Pattern backslashes = Pattern.compile("\\\\(?=[\\`@%&])");

    /**
     * This method takes care of converting a structure comment section into Markdown.
     * It first pre-processes it, to insert links.  If there's an exception, it will
     * print an error message and then return the original, or preprocessed string.
     */

    public String convertMarkdown(String s) {
        String html = s;
        Matcher m;

        // Preprocess links
        // FIXME: right now these are preprocessed for both XML and Markdown annotations.
        // Is that what we want?  See #45. 
        m = attrLink.matcher(s);
        s = m.replaceAll("<a href='#p=attr-$1'>@$1</a>");
        m = paramEntLink.matcher(s);
        s = m.replaceAll("<a href='#p=pe-$1'>%$1;</a>");
        m = genEntLink.matcher(s);
        s = m.replaceAll("<a href='#p=ge-$1'>&$2;</a>");
        m = elemLink.matcher(s);
        s = m.replaceAll("<a href='#p=elem-$1'>&lt;$1&gt;</a>");

        if (!commentProcessor.equals("")) {
            try {
                Process cproc = Runtime.getRuntime().exec(commentProcessor);

                // The output stream of the process is piped into the stdin of the comment
                // processor.
                PrintWriter pos = new PrintWriter(cproc.getOutputStream());
                pos.print(s);
                pos.close();

                // The input stream of the process is where we get the output of the comment
                // processor (XHTML format)
                InputStream is = cproc.getInputStream();
                html = IOUtils.toString(is, "UTF-8");
                is.close();
                //System.err.println("html output is " + html);
            }
            catch (IOException e) {
                System.err.println("Error interpreting comment as markdown: " + e.getMessage());
            }
        }
        else {
            m = backslashes.matcher(s);
            s = m.replaceAll("");
            html = s;
        }
        return html;
    }

    public String getSection(String name) {
        return (String) sections.get(name);
    }

    /**
     * Returns an iterator of all the names of the sections in this structured
     * comment
     */
    public Iterator getSectionNameIterator() {
        return sections.keySet().iterator();
    }

    /**
     * Sets the structured comment processor for this class.
     */
    public static void setCommentProcessor(String p) {
        commentProcessor = p;
    }

    /**
     * Returns true if this structured comment includes a tag "root"
     */
    public boolean isRoot() {
        return root;
    }
}
