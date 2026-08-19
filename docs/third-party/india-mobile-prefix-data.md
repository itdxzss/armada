# India mobile prefix data attribution

The India mobile prefix rows in `V129__group_creator_phone_region.sql` are derived from
[hstsethi/in-mob-prefix](https://github.com/hstsethi/in-mob-prefix), commit
`153ba809d514e74f62a1dc88fb10f0cb1a562e0e`, licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

Modifications made by Armada:

- removed operator names because the product only needs the original allocation region;
- removed rows whose circle value is empty;
- deduplicated rows by four-digit national mobile prefix;
- mapped telecom-circle codes to Chinese display names;
- described the result as a phone-number allocation inference, not a current-location lookup.
