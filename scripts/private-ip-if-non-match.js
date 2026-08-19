function collectStrings(value, result) {
    if (typeof value === "string") {
        result.push(value);
        return;
    }

    if (Array.isArray(value)) {
        value.forEach(function (item) {
            collectStrings(item, result);
        });
        return;
    }

    if (value && typeof value === "object") {
        Object.keys(value).forEach(function (key) {
            collectStrings(value[key], result);
        });
    }
}

function isPrivateIpv4(value) {
    var parts = value.split(".");
    if (parts.length !== 4) {
        return false;
    }

    var octets = parts.map(function (part) {
        return Number(part);
    });
    var valid = octets.every(function (octet) {
        return Number.isInteger(octet) && octet >= 0 && octet <= 255;
    });
    if (!valid) {
        return false;
    }

    return octets[0] === 10 ||
        octets[0] === 127 ||
        (octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127) ||
        (octets[0] === 169 && octets[1] === 254) ||
        (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
        (octets[0] === 192 && octets[1] === 168);
}

var dnsValues = [];
collectStrings($request.dnsResult, dnsValues);
$done({ matched: true });
