/* Copyright 2011 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.doctor;

import java.io.*;
import java.text.*;
import java.util.*;
import org.apache.commons.codec.binary.*;

/* Parse a network status consensus or vote. */
public class Parser {

  /* Parse and return a consensus and corresponding votes, or null if
   * something goes wrong. */
  public SortedMap<String, Status> parse(
      List<Download> downloadedConsensuses,
      List<Download> downloadedVotes) {
    SortedSet<Status> parsedVotes = new TreeSet<Status>();
    for (Download downloadedVote : downloadedVotes) {
      String voteString = downloadedVote.getResponseString();
      long fetchTime = downloadedVote.getFetchTime();
      Status parsedVote = this.parseConsensusOrVote(voteString, fetchTime,
          false);
      if (parsedVote != null) {
        parsedVotes.add(parsedVote);
      }
    }
    SortedMap<String, Status> parsedConsensuses =
        new TreeMap<String, Status>();
    for (Download downloadedConsensus : downloadedConsensuses) {
      String nickname = downloadedConsensus.getAuthority();
      String consensusString = downloadedConsensus.getResponseString();
      long fetchTime = downloadedConsensus.getFetchTime();
      Status parsedConsensus = this.parseConsensusOrVote(consensusString,
          fetchTime, true);
      if (parsedConsensus != null) {
        for (Status parsedVote : parsedVotes) {
          if (parsedConsensus.getValidAfterMillis() ==
              parsedVote.getValidAfterMillis()) {
            parsedConsensus.addVote(parsedVote);
          }
        }
        parsedConsensuses.put(nickname, parsedConsensus);
      }
    }
    return parsedConsensuses;
  }

  /* Date-time formats to parse and format timestamps. */
  private static SimpleDateFormat dateTimeFormat;
  static {
    dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
  }

  /* Parse a consensus or vote string into a Status instance. */
  private Status parseConsensusOrVote(String statusString, long fetchTime,
      boolean isConsensus) {
    if (statusString == null) {
      return null;
    }
    Status status = new Status();
    status.setUnparsedString(statusString);
    status.setFetchTime(fetchTime);
    try {
      BufferedReader br = new BufferedReader(new StringReader(
          statusString));
      String line, rLine = null, sLine = null;
      int totalRelays = 0, runningRelays = 0, bandwidthWeights = 0;
      while ((line = br.readLine()) != null) {
        if (line.startsWith("consensus-method ") ||
            line.startsWith("consensus-methods ")) {
          SortedSet<Integer> consensusMethods = new TreeSet<Integer>();
          String[] parts = line.split(" ");
          for (int i = 1; i < parts.length; i++) {
            consensusMethods.add(Integer.parseInt(parts[i]));
          }
          status.setConsensusMethods(consensusMethods);
        } else if (line.startsWith("valid-after ")) {
          try {
            status.setValidAfterMillis(dateTimeFormat.parse(
                line.substring("valid-after ".length())).getTime());
          } catch (ParseException e) {
            System.err.println("Could not parse valid-after timestamp in "
                + "line '" + line + "' of a "
                + (isConsensus ? "consensus" : "vote") + ".  Skipping.");
            return null;
          }
        } else if (line.startsWith("client-versions ")) {
          status.setRecommendedClientVersions(
              new TreeSet<String>(Arrays.asList(
              line.split(" ")[1].split(","))));
        } else if (line.startsWith("server-versions ")) {
          status.setRecommendedServerVersions(
              new TreeSet<String>(Arrays.asList(
              line.split(" ")[1].split(","))));
        } else if (line.startsWith("known-flags ")) {
          for (String flag : line.substring("known-flags ".length()).
              split(" ")) {
            status.addKnownFlag(flag);
          }
        } else if (line.startsWith("params ")) {
          if (line.length() > "params ".length()) {
            for (String param :
                line.substring("params ".length()).split(" ")) {
              String paramName = param.split("=")[0];
              String paramValue = param.split("=")[1];
              status.addConsensusParam(paramName, paramValue);
            }
          }
        } else if (line.startsWith("dir-source ") && !isConsensus) {
          status.setNickname(line.split(" ")[1]);
          status.setFingerprint(line.split(" ")[2]);
        } else if (line.startsWith("dir-key-expires ")) {
          try {
            status.setDirKeyExpiresMillis(dateTimeFormat.parse(
                line.substring("dir-key-expires ".length())).getTime());
          } catch (ParseException e) {
            System.err.println("Could not parse dir-key-expires "
                + "timestamp in line '" + line + "' of a "
                + (isConsensus ? "consensus" : "vote") + ".  Skipping.");
            return null;
          }
        } else if (line.startsWith("r ") ||
            line.equals("directory-footer")) {
          if (rLine != null) {
            StatusEntry statusEntry = new StatusEntry();
            statusEntry.setNickname(rLine.split(" ")[1]);
            statusEntry.setFingerprint(Hex.encodeHexString(
                Base64.decodeBase64(rLine.split(" ")[2] + "=")).
                toUpperCase());
            SortedSet<String> flags = new TreeSet<String>();
            if (sLine.length() > 2) {
              for (String flag : sLine.substring(2).split(" ")) {
                flags.add(flag);
              }
            }
            statusEntry.setFlags(flags);
            status.addStatusEntry(statusEntry);
          }
          if (line.startsWith("r ")) {
            rLine = line;
          } else {
            break;
          }
        } else if (line.startsWith("s ") || line.equals("s")) {
          sLine = line;
          if (line.contains(" Running")) {
            runningRelays++;
          }
        } else if (line.startsWith("v ") &&
            sLine.contains(" Authority")) {
          String nickname = rLine.split(" ")[1];
          String versionString = line.substring(2);
          status.addAuthorityVersion(nickname, versionString);
        } else if (line.startsWith("w ") && !isConsensus &&
              line.contains(" Measured")) {
          bandwidthWeights++;
        }
      }
      br.close();
      status.setRunningRelays(runningRelays);
      status.setBandwidthWeights(bandwidthWeights);
    } catch (IOException e) {
      System.err.println("Caught an IOException while parsing a "
          + (isConsensus ? "consensus" : "vote") + " string.  Skipping.");
      return null;
    }
    return status;
  }
}

