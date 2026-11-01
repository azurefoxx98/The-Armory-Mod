# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# The mods The Armory is made to run beside, and the stats each one defines.

from __future__ import annotations
HEXCODE_STATS = frozenset({'Affinity_Arcane', 'Affinity_Fire', 'Affinity_Ice', 'Affinity_Life', 'Affinity_Lightning', 'Affinity_Void', 'Affinity_Water', 'Flag_IsHolding', 'InContext', 'Stability', 'Volatility'})
PAIRED_STATS = {name: 'Hexcode' for name in HEXCODE_STATS}

def paired_mod_of_stat(name: str) -> str | None:
    return PAIRED_STATS.get(name)
