"""Read acceleration must not weaken cross-connection gates or expose mutable cached state."""
import sqlite3

import pytest

from tap_explorer.store import GraphStore


def test_cached_documents_are_detached_and_own_writes_are_visible(tmp_path):
    with GraphStore(tmp_path / 'graph.db') as graph:
        graph.initialize({})
        exported = graph.document()
        exported['context']['poison'] = True
        assert 'poison' not in graph.document()['context']
        graph.add_observation('o', {'note': 'real'})
        assert graph.document()['observations']['o']['evidence']['note'] == 'real'
        with pytest.raises(ValueError), graph._change() as doc:
            doc['format'] = 'broken'
        assert graph.document()['format'] == 'tap-exploration/1'


def test_external_commits_invalidate_a_cached_reader_and_intent_gate(tmp_path):
    path = tmp_path / 'graph.db'
    with GraphStore(path) as first, GraphStore(path) as second:
        first.initialize({})
        first.add_observation('o', {})
        first.add_state('s', 'o', signature='s', depth=0)
        first.add_action('a', 's', 'tap', target={})
        first.approve_action('a', 'operator')
        assert second.next_action()['action'] == 'a'
        first.begin_attempt('intent', 'a', 'o')
        assert second.next_action()['reason'] == 'recovery_required'
        with pytest.raises(ValueError):
            second.begin_attempt('duplicate', 'a', 'o')
        assert second.document()['attempts']['intent']['status'] == 'intent'


@pytest.mark.parametrize('external', [False, True])
def test_corrupt_updates_are_validated_even_after_caching(tmp_path, external):
    path = tmp_path / 'graph.db'
    with GraphStore(path) as graph:
        graph.initialize({})
        graph.document()
        if external:
            with sqlite3.connect(path) as other:
                other.execute('UPDATE graph SET document=? WHERE id=1', ('{}',))
        else:
            graph._db.execute('UPDATE graph SET document=? WHERE id=1', ('{}',))
        with pytest.raises(ValueError):
            graph.document()
