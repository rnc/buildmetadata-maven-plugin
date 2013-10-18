/*
 * Copyright 2006-2013 smartics, Kronseder & Reiner GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.smartics.maven.plugin.buildmetadata;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.ObjectUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;

/**
 * Configuration to determine the commandline configuration.
 */
public class CommandLineConfig
{
  // ********************************* Fields *********************************

  // --- constants ------------------------------------------------------------

  /**
   * The command to be executed per default.
   */
  public static final String DEFAULT_PS_EXEC =
      "${env.JAVA_HOME}/bin/jps -m -v -V";

  /**
   * The placeholder allowed in the {@link #psExec psExec} to be replaced by a
   * PID determined by the plugin. If this place holder is not found in the
   * execution string, the PID is not calculated.
   */
  public static final String PID_PLACE_HOLDER = "${pid}";

  /**
   * The command to be executed per default.
   */
  public static final String DEFAULT_RESULT_REG_EXP = "${pid} (.+)";

  // --- members --------------------------------------------------------------

  /**
   * A command to execute to find a process that shows the command line
   * arguments.
   */
  private String psExec;

  /**
   * The grep on the process table generated by the {@link #psExec psExec}. If
   * <code>null</code> the result of the {@link #psExec psExec} will be
   * considered as the result to use as the command line information.
   */
  private String resultRegExp;

  /**
   * Flag to skip the ps execution.
   */
  private boolean skip;

  // ****************************** Initializer *******************************

  // ****************************** Constructors ******************************

  // ****************************** Inner Classes *****************************

  // ********************************* Methods ********************************

  // --- init -----------------------------------------------------------------

  /**
   * Creates the default instance.
   *
   * @return the default instance.
   */
  public static CommandLineConfig createDefault()
  {
    final CommandLineConfig instance = new CommandLineConfig();
    instance.psExec = DEFAULT_PS_EXEC;
    instance.resultRegExp = DEFAULT_RESULT_REG_EXP;
    return instance;
  }

  // --- get&set --------------------------------------------------------------

  /**
   * Returns a command to execute to find a process that shows the command line
   * arguments.
   *
   * @return a command to execute to find a process that shows the command line
   *         arguments.
   */
  public String getPsExec()
  {
    return psExec;
  }

  /**
   * Returns the grep on the process table generated by the {@link psExec}. If
   * <code>null</code> the result of the <code>psExec</code> will be considered
   * as the result to use as the command line information.
   *
   * @return the grep on the process table generated by the {@link psExec}.
   */
  public String getResultRegExp()
  {
    return resultRegExp;
  }

  // --- business -------------------------------------------------------------

  /**
   * Runs the calculation of the command line.
   *
   * @param executionProperties the execution properties to search for command
   *          line properties.
   * @return the command line or <code>null</code> if none has been found.
   */
  public String calcCommandLine(final Properties executionProperties)
  {
    String commandLine = probEnv(executionProperties);

    if (StringUtils.isNotBlank(commandLine))
    {
      return commandLine;
    }

    commandLine = probeSunVm(executionProperties);

    if (StringUtils.isNotBlank(commandLine))
    {
      return commandLine;
    }

    commandLine = probePsExec(executionProperties);

    if (StringUtils.isBlank(commandLine))
    {
      commandLine = probeJmx();
    }

    return commandLine;
  }

  private static String probEnv(final Properties executionProperties)
  {
    // Typically works on Windows
    final String commandLine =
        executionProperties.getProperty("env.MAVEN_CMD_LINE_ARGS");
    return commandLine;
  }

  private static String probeSunVm(final Properties executionProperties)
  {
    // Might work on some machines and does not hurt too much to try ...
    String commandLine = executionProperties.getProperty("sun.java.command");
    if (commandLine != null)
    {
      final String prefix =
          "org.codehaus.plexus.classworlds.launcher.Launcher ";
      if (commandLine.startsWith(prefix))
      {
        commandLine = commandLine.substring(prefix.length());
      }
    }
    return commandLine;
  }

  private String probePsExec(final Properties executionProperties)
  {
    if (skip)
    {
      return null;
    }

    final Long pid =
        (hasPid(psExec) || hasPid(resultRegExp)) ? getProcessId() : null;
    final String expandedCommand =
        expand(executionProperties, normalize(psExec), pid);
    final String expandedRegExp =
        expand(executionProperties, resultRegExp, pid);

    final String commandLine = runPs(expandedCommand, expandedRegExp);
    return commandLine;
  }

  private static String normalize(final String psExec)
  {
    if (psExec != null && '/' != File.separatorChar)
    {
      return psExec.replace('/', File.separatorChar);
    }
    return psExec;
  }

  private String runPs(final String expandedCommand, final String expandedRegExp)
  {
    try
    {
      final Process process = Runtime.getRuntime().exec(expandedCommand);
      try
      {
        try
        {
          process.waitFor();
        }
        catch (final InterruptedException e)
        {
          // continue
        }
        final int exit = process.exitValue();
        if (exit == 0)
        {
          final String result = IOUtil.toString(process.getInputStream());
          final String commandLine;
          if (expandedRegExp != null)
          {
            final Pattern pattern = Pattern.compile(expandedRegExp);
            commandLine = grep(pattern, result);
          }
          else
          {
            commandLine = result;
          }
          return commandLine;
        }
      }
      finally
      {
        process.destroy();
      }
    }
    catch (final IOException e)
    {
      System.err.println(e.getMessage());
      e.printStackTrace();
      // Silently ignore that the command failed.
    }
    return null;
  }

  private String expand(final Properties executionProperties,
      final String input, final Long pid)
  {
    if (input == null)
    {
      return null;
    }

    String expanded = input;
    if (pid != null)
    {
      expanded = expanded.replace(PID_PLACE_HOLDER, String.valueOf(pid));
    }

    if (expanded.contains("${JAVA_HOME}"))
    {
      final String javaHome = executionProperties.getProperty("JAVA_HOME");
      if (javaHome != null)
      {
        expanded = expanded.replace(PID_PLACE_HOLDER, String.valueOf(pid));
      }
    }

    if (expanded.contains("${"))
    {
      for (final Entry<Object, Object> entry : executionProperties.entrySet())
      {
        final String key = ObjectUtils.toString(entry.getKey());
        final String placeholder = "${" + key + '}';
        final String value = ObjectUtils.toString(entry.getValue());
        expanded = expanded.replace(placeholder, value);
        if (!expanded.contains("${"))
        {
          break;
        }
      }
    }

    return expanded;
  }

  private static boolean hasPid(final String input)
  {
    return (input != null && input.contains(PID_PLACE_HOLDER));
  }

  private String grep(final Pattern pattern, final String result)
  {
    final String[] lines = result.split(System.getProperty("line.separator"));
    if (lines.length == 1)
    {
      return lines[0];
    }

    for (final String line : lines)
    {
      final Matcher matcher = pattern.matcher(line);
      if (matcher.find())
      {
        if (matcher.groupCount() > 0)
        {
          final String commandLine = matcher.group(1);
          return commandLine;
        }
        else
        {
          return line;
        }
      }
    }
    return result;
  }

  // see
  // http://stackoverflow.com/questions/35842/how-can-a-java-program-get-its-own-process-id
  private static Long getProcessId()
  {
    final String name = ManagementFactory.getRuntimeMXBean().getName();
    final int index = name.indexOf('@');

    if (index > 0)
    {
      try
      {
        return Long.parseLong(name.substring(0, index));
      }
      catch (final NumberFormatException e)
      {
        // return null at the end
      }
    }
    return null;
  }

  private static String probeJmx()
  {
    // Won't get too much useful, but may be better than nothing ...
    final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    final String commandLine = runtime.getInputArguments().toString();
    return commandLine;
  }

  // --- object basics --------------------------------------------------------

}