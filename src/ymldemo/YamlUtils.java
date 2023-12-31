package ymldemo;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.constructor.Constructor;

class Rep {
    String from;
    String to;
}

public class YamlUtils {

    // These options are here so we can make use of the comment processing
    // feature of snakeyaml.
    LoaderOptions loaderOptions;
    DumperOptions dumperOptions;
    Yaml yaml;

    public YamlUtils() {
        loaderOptions = new LoaderOptions();
        loaderOptions.setProcessComments(true);

        dumperOptions = new DumperOptions();
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setProcessComments(true);

        Constructor cstr = new Constructor(loaderOptions);
        Representer repr = new Representer(dumperOptions);

        yaml = new Yaml(cstr, repr, dumperOptions, loaderOptions);
    }
   

    private static String repeated(char c, int len) {
        return new String(new char[len]).replace('\0', c);
    }

    /**
     * Convenience function to print to <code>System.out</code> a visual boundary between
     * lines consisting of a message centered in a sequence of a single character repeated
     * to the width of 80 characters.
     * @param c the character to repeat
     * @param msg the message to put inside the repeated characters.
     */
    private static void printBoundary(char c, String msg) {
        int l = (78 - msg.length())/2;
        System.out.println("\n" + repeated(c, l) + " " + msg + " " + repeated(c, l) + "\n");
    }

    /**
     * Trim only the trailing spaces from a string
     * @param str the string to trim the spaces from.
     * @return the resulting string after the trim operation.
     */
    private static String trimTrailingBlanks(String str)
    {
        if( str == null)
            return "";
        int len = str.length();
        for( ; len > 0; len--)
        {
            if( !Character.isWhitespace(str.charAt(len - 1)))
                break;
        }
        return str.substring( 0, len);
    } 

    /**
     * Handle triple-quoted literal blocks.
     * 
     * We have the requirement of supporting a different literal block style started with three
     * single-quotes.  This doesn't require additional indentation and allows the user to paste
     * code copied from an editor and guarantees that the leading spaces will be kept.  It is
     * a pain to manage this but it is a requirement of our users.
     * 
     * The approach is to maintain a list of substitutions to make in the original string
     * and return this so it can be performed separately.  We may want to combine this last
     * step into this function in the future so we simply return the modified text.
     * 
     * @param txt a <code>String</code> with the contents of the YAML file.
     * @return a <code>List&lt;Rep&gt;</code> containing all the strings (normally multi-line)
     *         in the original text that have to be replaced, and their replacements.
     */
    private static List<Rep> extractTripleQuoteStr(String txt) {
        List<Rep> result = new ArrayList<>();

        Pattern pattern = Pattern.compile("^(\s*)([a-zA-Z0-9_]+?):\s*'''(.*?)'''", Pattern.MULTILINE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(txt);

        while (matcher.find()) {
            Rep rep = new Rep();
            rep.from = matcher.group(0);

            int baseIndentLen = matcher.group(1).length() + 2;
            String prefix = repeated(' ', baseIndentLen);
            String content = 
                matcher.group(3).lines()
                    .map((String s) -> trimTrailingBlanks(prefix + s))
                    .skip(1)  // regex includes leading cr which results in a blank line.
                    .collect(Collectors.joining("\n"))
                    .toString();
            rep.to = 
                (matcher.group(1) + matcher.group(2)) + 
                ": |2\n" +  // The 2 ensures indentation is preserved by parser/dumper
                content;
            result.add(rep);
        }

        return result;
    }

    /**
     * Load a yaml-file, performing any preprocessing necessary to handle the triple-quoted literal blocks.
     * @param ymlFile the path to the yaml-file
     * @return a <code>Node</code> object that is the root of the resulting graph.
     */
    public Node loadYml(String ymlFile) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(ymlFile), "UTF-8"));
            StringBuilder txtBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                txtBuilder.append(line).append("\n");
            }
            reader.close();

            String txt = txtBuilder.toString();

            printBoundary('*', "Original YAML String");
            System.out.println(txt);

            List<Rep> reps = extractTripleQuoteStr(txt);

            for (Rep rep : reps) {
                txt = txt.replace(rep.from, rep.to);
            }

            printBoundary('*', "Pre-Processed YAML String");
            System.out.println(txt);

            StringReader rdr = new StringReader(txt);
            
            return yaml.compose(rdr);
        } catch (IOException e) {
            System.err.println("ERROR: cannot find file \"" + ymlFile + "\"");
            return null;
        }
    }

    /**
     * Get a child node of a specified parent node with the given name.
     * @param parent  the parent node
     * @param childName the name of the child node.
     * @return the child <code>Node</code>, or <code>null</code> if a child 
     *         with the given name doesn't exist.
     * @throws IllegalArgumentException if the parent node is not a 
     *         <code>MappingNode</code>
     */
    private Node getChildNode(Node parent, String childName) {
        if (parent == null) return null;
        if (parent.getNodeId() == NodeId.scalar) {
            String msg = "Parent node is scalar and has no child named " + childName;
            throw new IllegalArgumentException(msg);
        } else if (parent.getNodeId() == NodeId.sequence) {
            String msg = "Parent node is a sequence and has no named child nodes (" +
                        childName + ")";
            throw new IllegalArgumentException(msg);
        } else if (parent.getNodeId() == NodeId.mapping) {
            MappingNode mapping = (MappingNode)parent;
            for (NodeTuple nt : mapping.getValue()) {
                ScalarNode key = (ScalarNode) nt.getKeyNode();
                if (key.getValue().equals(childName)) return nt.getValueNode();
                //System.out.println(key.getValue());
            }
            throw new IllegalArgumentException("No child node named " + childName);
        }
        return null;
    }

    /**
     * Get a <code>Node</code> object with the given path.  The path is specified by
     * using '/' to separate the different levels of the graph.  This is sufficient for
     * our case since this is defined to be an invalid character for node names in our
     * application.
     * @param root the root <code>Node</code> of the graph
     * @param name the name of the <code>Node</code> to find.
     * @return the <code>Node</code>, if found.  <code>null</code> otherwise.
     */
    private Node getNodeByName(Node root, String name) {
        Node res = root;

        List<String> nameParts = Arrays.asList(name.split("/"));
        for (String part : nameParts) {
            res = getChildNode(res, part);
        }
        return res;
    }

    private String getScalarValue(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("Node doesn't exist");
        }
        if (node.getNodeId() == NodeId.sequence) {
            String msg = "Node is a sequence and doesn't have a scalar value";
            throw new IllegalArgumentException(msg);
        } else if (node.getNodeId() == NodeId.mapping) {
            String msg = "Node is a map and doesn't have a scalar value";
            throw new IllegalArgumentException(msg);
        }
        ScalarNode sc = (ScalarNode) node;
        return sc.getValue();
    }

    private List<String> getListValue(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("Node doesn't exist");
        }
        if (node.getNodeId() == NodeId.scalar) {
            String msg = "Node is a scalar and doesn't have a sequence value";
            throw new IllegalArgumentException(msg);
        } else if (node.getNodeId() == NodeId.mapping) {
            String msg = "Node is a map and doesn't have a sequence value";
            throw new IllegalArgumentException(msg);
        }
        SequenceNode sc = (SequenceNode) node;
        return sc.getValue().stream()
            .map((Node n) -> getScalarValue(n))
            .collect(Collectors.toList());
    }

    // private void setScalarValue(Node node, String value) {
    //     if (node == null) {
    //         throw new IllegalArgumentException("Node doesn't exist");
    //     }
    //     if (node.getNodeId() == NodeId.sequence) {
    //         String msg = "Node is a sequence and doesn't have a scalar value";
    //         throw new IllegalArgumentException(msg);
    //     } else if (node.getNodeId() == NodeId.mapping) {
    //         String msg = "Node is a map and doesn't have a scalar value";
    //         throw new IllegalArgumentException(msg);
    //     }
    //     ScalarNode sc = (ScalarNode) node;
    //     // setValue doesn't exist!!!
    //     sc.setValue(value);
    // }

    // private void setListValue(Node node, List<String> vals) {
    //     if (node == null) {
    //         throw new IllegalArgumentException("Node doesn't exist");
    //     }
    //     if (node.getNodeId() == NodeId.scalar) {
    //         String msg = "Node is a scalar and doesn't have a sequence value";
    //         throw new IllegalArgumentException(msg);
    //     } else if (node.getNodeId() == NodeId.mapping) {
    //         String msg = "Node is a map and doesn't have a sequence value";
    //         throw new IllegalArgumentException(msg);
    //     }
    //     SequenceNode sc = (SequenceNode) node;
        
    //     Iterator<Node> itNode = sc.getValue().iterator();
    //     Iterator<String> itVal = vals.iterator;

    //     while (itNode.hasNext() && itVal.hasNext()) {
    //         setScalarValue(itNode.next(), itVal.next());
    //     }
    // }

    public static void main(String[] args) {
        String ymlFile = "data/ocho_config-simple.yml";
        YamlUtils ut = new YamlUtils();
        Node root = ut.loadYml(ymlFile);
        if (root != null) {
            StringWriter writer = new StringWriter();
            ut.yaml.serialize(root, writer);
            String prettyYaml = writer.toString();

            printBoundary('>', "Generated YAML");
            System.out.println(prettyYaml);
        }

        String nodeName = "func_level_per_spec/import_for_value";
        Node g1 = ut.getNodeByName(root, nodeName);
        List<String> g2v = ut.getListValue(g1);
        printBoundary('*', nodeName);
        g2v.stream()
            .forEach((String s) -> System.out.println(s));

        nodeName = "tdl_excluded_pins_spec/pin_setup_content";
        g1 = ut.getNodeByName(root, nodeName);
        String g1v = ut.getScalarValue(g1);
        printBoundary('*', nodeName);
        System.out.println(g1v);
 
        nodeName = "gen_files/domainlevelvalues.spec/content";
        g1 = ut.getNodeByName(root, nodeName);
        g1v = ut.getScalarValue(g1);
        printBoundary('*', nodeName);
        System.out.println(g1v);

        // List<String> ls = Arrays.asList("newVal1", "newVal2");
        // nodeName = "func_level_per_spec/import_for_value";
        // g2v = ut.getNodeByName(root, nodeName);
        // setListValue(g1, ls);
        // printBoundary('*', nodeName + " after update");
        // g2v.stream()
        //     .forEach((String s) -> System.out.println(s));
   }
}
