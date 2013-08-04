#!/usr/bin/env python

"""
Downloads the present server descriptors, extrainfo descriptors, and consensus
checking for any malformed entries. This is meant to be ran hourly to ensure
that the directory authorities don't publish anything that's invalid. This
issues an email notification when a problem is discovered.
"""

import datetime
import os
import traceback

import util

import stem.descriptor
import stem.descriptor.remote

EMAIL_SUBJECT = 'Unable to retrieve tor descriptors'

EMAIL_BODY = """\
Unable to retrieve the present %s...

source: %s
time: %s
error: %s
"""

log = util.get_logger('descriptor_checker')
util.log_stem_debugging('descriptor_checker')


def main():
  # retrieve the server and extrainfo descriptors from any authority

  targets = [
    ('server descriptors', '/tor/server/all.z'),
    ('extrainfo descriptors', '/tor/extra/all.z'),
  ]

  for descriptor_type, resource in targets:
    log.debug("Downloading %s..." % descriptor_type)

    query = stem.descriptor.remote.Query(
      resource,
      timeout = 60,
    )

    query.run(True)

    if not query.error:
      count = len(list(query))
      log.debug("  %i descriptors retrieved from %s in %0.2fs" % (count, query.download_url, query.runtime))
    else:
      log.warn("Unable to retrieve the %s: %s" % (descriptor_type, query.error))
      send_email(descriptor_type, query)

  # download the consensus from each authority

  for authority, endpoint in stem.descriptor.remote.DIRECTORY_AUTHORITIES.items():
    log.debug("Downloading the consensus from %s..." % authority)

    query = stem.descriptor.remote.Query(
      '/tor/status-vote/current/consensus.z',
      timeout = 60,
      endpoints = [endpoint],
      document_handler = stem.descriptor.DocumentHandler.DOCUMENT,
    )

    query.run(True)

    if not query.error:
      count = len(list(query)[0].routers)
      log.debug("  %i descriptors retrieved from %s in %0.2fs" % (count, query.download_url, query.runtime))
    else:
      log.warn("Unable to retrieve the consensus from %s: %s" % (authority, query.error))
      send_email('consensus', query)


def send_email(descriptor_type, query):
  try:
    timestamp = datetime.datetime.now().strftime("%m/%d/%Y %H:%M")
    util.send(EMAIL_SUBJECT, body_text = EMAIL_BODY % (descriptor_type, query.download_url, timestamp, query.error))
  except Exception, exc:
    log.warn("Unable to send email: %s" % exc)


if __name__ == '__main__':
  try:
    main()
  except:
    log.error("Script failed:\n%s" % traceback.format_exc())