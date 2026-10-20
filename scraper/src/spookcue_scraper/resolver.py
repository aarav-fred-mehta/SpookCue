"""Jump scare anchor resolver.

Anchors each jump scare timestamp to the closest preceding dialogue line within 60s
so synchronization is resilient to different cuts and release editions.
"""

import logging
from typing import Any, Dict, List

logger = logging.getLogger(__name__)


def resolve_scares_to_anchors(
    scares: List[Dict[str, Any]],
    subtitles: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Anchor each scare to preceding subtitle line or mark clock-only."""
    # Phase 1 implementation
    return []
