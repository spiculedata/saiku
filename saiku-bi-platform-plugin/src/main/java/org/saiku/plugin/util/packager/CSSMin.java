/**
CSSMin Copyright License Agreement (BSD License)

Copyright (c) 2011, Barry van Oudtshoorn
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

* Redistributions of source code must retain the above
copyright notice, this list of conditions and the
following disclaimer.

* Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the
following disclaimer in the documentation and/or other
materials provided with the distribution.

* Neither the name of Barryvan nor the names of its
contributors may be used to endorse or promote products
derived from this software without specific prior
written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
*/
package org.saiku.plugin.util.packager;

import java.util.*;
import java.util.regex.*;
import java.io.*;

public class CSSMin
{

  protected static boolean bDebug = false;

  public static void main(String[] args)
  {
    if (args.length < 1)
    {
      System.out.println("Usage: ");
      System.out.println("CSSMin [Input file] [Output file] [DEBUG]");
      System.out.println("If no output file is specified, stdout will be used.");
      return;
    }

    bDebug = (args.length > 2);

    PrintStream out;

    if (args.length > 1)
    {
      try
      {
        out = new PrintStream(args[1]);
      }
      catch (Exception e)
      {
        System.err.println("Error outputting to " + args[1] + "; redirecting to stdout");
        out = System.out;
      }
    }
    else
    {
      out = System.out;
    }

    formatFile(args[0], out);
  }

  public static void formatFile(String f, PrintStream out)
  {
    try
    {
      formatFile(new FileReader(f), out);
    }
    catch (Exception e)
    {
      System.out.println(e.getMessage());
    }
  }

  public static void formatFile(Reader input, OutputStream out)
  {
    formatFile(input, new PrintStream(out));
  }

  public static void formatFile(Reader input, PrintStream out)
  {
    try
    {
      int k, n;

      BufferedReader br = new BufferedReader(input);
      StringBuffer sb = new StringBuffer();

      if (bDebug)
      {
        System.err.println("Removing extraneous whitespace...");
      }
      String s;
      while ((s = br.readLine()) != null)
      {
        if (s.trim().equals(""))
        {
          continue;
        }
        sb.append(s.replaceAll("[\t\n\r]", "").replaceAll("  ", " "));
      }

      if (bDebug)
      {
        System.err.println("Removing comments...");
      }
      // Find the start of the comment
      n = 0;
      while ((n = sb.indexOf("/*", n)) != -1)
      {
        if (sb.charAt(n + 2) == '*')
        { // Retain special comments
          n += 2;
          continue;
        }
        k = sb.indexOf("*/", n + 2);
        if (k == -1)
        {
          throw new Exception("Unterminated comment. Aborting.");
        }
        sb.delete(n, k + 2);
      }

      if (bDebug)
      {
        System.err.println("Parsing and processing selectors...");
      }
      Vector<Selector> selectors = new Vector<Selector>();
      n = 0;
      while ((k = sb.indexOf("}", n)) != -1)
      {
        try
        {
          selectors.addElement(new Selector(sb.substring(n, k + 1)));
        }
        catch (Exception e)
        {
          System.out.println(e.getMessage());
        }
        n = k + 1;
      }

      for (Selector selector : selectors)
      {
        out.print(selector.toString());
      }
      out.print("\r\n");

      out.close();

      if (bDebug)
      {
        System.err.println("Process completed successfully.");
      }

    }
    catch (Exception e)
    {
      System.out.println(e.getMessage());
    }

  }
}

class Selector
{

  private Property[] properties;
  private String selector;

  /**
   * Creates a new Selector using the supplied strings.
   * @param selector The selector; for example, "div { border: solid 1px red; color: blue; }"
   */
  public Selector(String selector) throws Exception
  {
    String[] parts = selector.split("\\{"); // We have to escape the { with a \ for the regex, which itself requires escaping for the string. Sigh.
    if (parts.length < 2)
    {
      throw new Exception("Warning: Incomplete selector: " + selector);
    }
    this.selector = parts[0].trim();
    String contents = parts[1].trim();
    if (CSSMin.bDebug)
    {
      System.err.println("Parsing selector: " + this.selector);
      System.err.println("\t" + contents);
    }
    if (contents.charAt(contents.length() - 1) != '}')
    { // Ensure that we have a leading and trailing brace.
      throw new Exception("Unterminated selector: " + selector);
    }
    if (contents.length() == 1)
    {
      throw new Exception("Empty selector body: " + selector);
    }
    contents = contents.substring(0, contents.length() - 2);
    this.properties = parseProperties(contents);
    sortProperties(this.properties);
  }

  /**
   * Prints out this selector and its contents nicely, with the contents sorted alphabetically.
   */
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(this.selector).append("{");
    for (Property p : this.properties)
    {
      sb.append(p.toString());
    }
    sb.append("}");
    return sb.toString();
  }

  /**
   * Parses out the properties of a selector's body.
   * @param contents The body; for example, "border: solid 1px red; color: blue;"
   */
  private Property[] parseProperties(String contents)
  {
    ArrayList<String> parts = new ArrayList<String>();
    boolean bCanSplit = true;
    int j = 0;
    for (int i = 0; i < contents.length(); i++)
    {
      if (!bCanSplit)
      { // If we're inside a string
        bCanSplit = (contents.charAt(i) == '"');
      }
      else if (contents.charAt(i) == '"')
      {
        bCanSplit = false;
      }
      else if (contents.charAt(i) == ';')
      {
        parts.add(contents.substring(j, i));
        j = i + 1;
      }
    }
    parts.add(contents.substring(j, contents.length()));

    ArrayList<Property> resultsList = new ArrayList<Property>();
    for (int i = 0; i < parts.size(); i++)
    {
      try
      {
        resultsList.add(new Property(parts.get(i)));
      }
      catch (Exception e)
      {
        System.out.println(e.getMessage());
      }
    }

    return resultsList.toArray(new Property[resultsList.size()]);
  }

  private void sortProperties(Property[] properties)
  {
    Arrays.sort(properties);
  }
}

class Property implements Comparable<Property>
{

  protected String property;
  protected Part[] parts;

  /**
   * Creates a new Property using the supplied strings. Parses out the values of the property selector.
   * @param property The property; for example, "border: solid 1px red;" or "-moz-box-shadow: 3px 3px 3px rgba(255, 255, 0, 0.5);".
   */
  public Property(String property) throws Exception
  {
    try
    {
      // Parse the property.
      ArrayList<String> parts = new ArrayList<String>();
      boolean bCanSplit = true;
      int j = 0;
      if (CSSMin.bDebug)
      {
        System.err.println("Examining property: " + property);
      }
      for (int i = 0; i < property.length(); i++)
      {
        if (!bCanSplit)
        { // If we're inside a string
          bCanSplit = (property.charAt(i) == '"');
        }
        else if (property.charAt(i) == '"')
        {
          bCanSplit = false;
        }
        else if (property.charAt(i) == ':')
        {
          parts.add(property.substring(j, i));
          j = i + 1;
        }
      }
      parts.add(property.substring(j, property.length()));
      if (parts.size() < 2)
      {
        throw new Exception("Warning: Incomplete property: " + property);
      }
      this.property = parts.get(0).trim().toLowerCase();

      this.parts = parseValues(parts.get(1).trim().replaceAll(", ", ","));

    }
    catch (PatternSyntaxException e)
    {
      // Invalid regular expression used.
    }
  }

  /**
   * Prints out this property nicely, with the contents sorted in a standardised order.
   */
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(this.property).append(":");
    for (Part p : this.parts)
    {
      sb.append(p.toString()).append(",");
    }
    sb.deleteCharAt(sb.length() - 1); // Delete the trailing comma.
    sb.append(";");
    return sb.toString();
  }

  public int compareTo(Property other)
  {
    return this.property.compareTo(other.property);
  }

  private Part[] parseValues(String contents)
  {
    String[] parts = contents.split(",");
    Part[] results = new Part[parts.length];

    for (int i = 0; i < parts.length; i++)
    {
      try
      {
        results[i] = new Part(parts[i]);
      }
      catch (Exception e)
      {
        System.out.println(e.getMessage());
        results[i] = null;
      }
    }

    return results;
  }
}

class Part
{

  String contents;

  public Part(String contents) throws Exception
  {
    // Many of these regular expressions are adapted from those used in the YUI CSS Compressor.

    // For simpler regexes.
    this.contents = " " + contents;

    // Replace 0in, 0cm, etc. with just 0
    this.contents = this.contents.replaceAll("(\\s)(0)(px|em|%|in|cm|mm|pc|pt|ex)", "$1$2");

    // Replace 0.6 with .6
    this.contents = this.contents.replaceAll("(\\s)0+\\.(\\d+)", "$1.$2");

    this.contents = this.contents.trim();

    // Simplify multiple zeroes
    if (this.contents.equals("0 0 0 0"))
    {
      this.contents = "0";
    }
    if (this.contents.equals("0 0 0"))
    {
      this.contents = "0";
    }
    if (this.contents.equals("0 0"))
    {
      this.contents = "0";
    }

    //simplifyColours();
  }

  private void simplifyColours()
  {
    if (CSSMin.bDebug)
    {
      System.out.println("Simplifying colours; contents is " + this.contents);
    }
    // Convert rgb() colours to Hex
    if (this.contents.toLowerCase().indexOf("rgb(") == 0)
    {
      String[] parts = this.contents.substring(4, this.contents.indexOf(")")).split(",");
      if (parts.length == 3)
      {
        int r = Integer.parseInt(parts[0], 10);
        int g = Integer.parseInt(parts[1], 10);
        int b = Integer.parseInt(parts[2], 10);

        StringBuffer sb = new StringBuffer();
        sb.append("#");
        if (r < 16)
        {
          sb.append("0");
        }
        sb.append(Integer.toHexString(r));
        if (g < 16)
        {
          sb.append("0");
        }
        sb.append(Integer.toHexString(g));
        if (b < 16)
        {
          sb.append("0");
        }
        sb.append(Integer.toHexString(b));

        this.contents = sb.toString();
      }
    }

    // Replace #223344 with #234
    if ((this.contents.indexOf("#") == 0) && (this.contents.length() == 7))
    {
      this.contents = this.contents.toLowerCase(); // Always have hex colours in lower case.
      if ((this.contents.charAt(1) == this.contents.charAt(2))
          && (this.contents.charAt(3) == this.contents.charAt(4))
          && (this.contents.charAt(5) == this.contents.charAt(6)))
      {
        StringBuffer sb = new StringBuffer();
        sb.append("#").append(this.contents.charAt(1)).append(this.contents.charAt(3)).append(this.contents.charAt(5));
        this.contents = sb.toString();
      }
    }
  }

  public String toString()
  {
    return this.contents;
  }
}
