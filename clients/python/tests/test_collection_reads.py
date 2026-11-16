"""Device acceptance for scoped collection values; run only with TAP_SERIALS set."""

from decimal import Decimal

from conftest import PACKAGE, launch

from tap_e2e import checkable, desc, res


def test_bulk_titles_prices_and_checkbox_state(tap_device):
    app = launch(tap_device, ".CollectionActivity")
    app.wait(res("products")).visible()
    titles = res("products").descendant(res("product_title"))
    assert app.elements(titles).texts() == ["Socks", "Shirt", "Coat"]
    # any_of forces the traversal plan; it must preserve native match order and scope.
    assert app.elements(titles | res("missing_product")).texts() == ["Socks", "Shirt", "Coat"]
    assert app.elements(res("product_title")).texts() == ["Outside the list", "Socks", "Shirt", "Coat"]
    assert tap_device.app("com.example.not.the.fixture").elements(res("product_title")).texts() == []
    assert tap_device.screen.elements(titles).texts() == ["Socks", "Shirt", "Coat"]

    prices = app.elements(res("products").descendant(res("product_price"))).texts()
    assert prices == ["4.50", "9.99", "30.00"]
    amounts = []
    for value in prices:
        assert value is not None
        amounts.append(Decimal(value))
    assert amounts == sorted(amounts)

    boxes = app.elements(res("products").descendant(checkable()))
    initial = boxes.snapshots()
    assert [(item.content_description, item.checked, item.enabled) for item in initial] == [
        ("Select socks", True, True),
        ("Select shirt", False, True),
        ("Select coat", True, False),
    ]
    assert all(item.checkable and item.package_name == PACKAGE for item in initial)
    app.element(desc("Select shirt")).tap()
    app.wait(desc("Select shirt")).checked()
    assert [item.checked for item in boxes.snapshots()] == [True, True, True]
    assert [item.checked for item in initial] == [True, False, True]
