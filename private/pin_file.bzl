"""Utilities for parsing and managing pin files."""

def parse_pin_file(content):
    """Parse a pin file and extract hash information.

    Args:
        content: The content of the pin file as a string.

    Returns:
        A dictionary mapping URLs to their hash values.
    """
    lines = content.split("\n")
    hashes = {}
    for line in lines:
        line = line.strip()
        if not line:
            continue
        space_index = line.find(" ")
        url = line[:space_index]
        hash = line[space_index + 1:].strip()
        hashes[url] = hash
    return hashes
